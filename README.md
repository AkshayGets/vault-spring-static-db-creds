# Vault static database roles + HikariCP, without restarts

A minimal Spring Boot application that reads its database credential from HashiCorp Vault
and keeps working when Vault rotates that credential — **no restart, no credential written
to disk, no configuration reload.**

---

## The problem this solves

A typical Spring application reads its database password once, at startup:

```
application.properties  ──read once at startup──▶  HikariCP keeps its own copy forever
```

HikariCP copies that password into its own configuration and uses that copy every time it
opens a new physical connection. It never re-reads the file. So when the password changes,
the only way to pick up the new one is to **restart the application**.

That is survivable when passwords change once a month during a maintenance window. It stops
being survivable when you want short-lived credentials.

### What this application does instead

```
Vault ──refreshed in the background──▶ credential held in memory
                                                  │
                                 read as each new connection opens
                                                  ▼
                                              HikariCP
```

The password is never written to a properties file, never written to disk, and never copied
into the pool's configuration. It is held in memory, kept current in the background, and read
at the one moment it is actually needed — and if it turns out to be stale, the application
refreshes it and retries.

---

## The client library

This uses **[Spring Cloud Vault](https://docs.spring.io/spring-cloud-vault/reference/)**
(`spring-cloud-starter-vault-config`), which builds on
**[Spring Vault](https://docs.spring.io/spring-vault/reference/)**.

Two things it gives us, both of which would otherwise be security-sensitive code we own:

| | What it handles |
|---|---|
| **Authentication** | logging in to Vault with the pod's Kubernetes identity, and keeping the Vault token renewed |
| **`VaultTemplate`** | a ready-made client object we call to read a path |

**Important:** we use it as a *library we call*, **not** as a configuration source. There is
deliberately no `spring.config.import: vault://` in this project — that mode injects Vault
values into Spring configuration at startup, which is exactly the "read once and copy into
the pool" behaviour being fixed here.

Reference material:

- [Spring Cloud Vault reference](https://docs.spring.io/spring-cloud-vault/reference/)
- [Spring Vault reference](https://docs.spring.io/spring-vault/reference/)
- [Vault database secrets engine — static roles](https://developer.hashicorp.com/vault/docs/secrets/databases#static-roles)
- [Vault Kubernetes auth method](https://developer.hashicorp.com/vault/docs/auth/kubernetes)
- [HashiCorp tutorial: reload secrets in Spring](https://developer.hashicorp.com/vault/tutorials/app-integration/spring-reload-secrets)
- [HikariCP configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)

---

## Static roles vs dynamic roles

Vault's database secrets engine offers two kinds of role. This project uses **static** roles.

| | **Static role** | **Dynamic role** |
|---|---|---|
| What Vault does | keeps one database user, changes its password on a schedule | creates a new user on demand, deletes it when the lease ends |
| Username | never changes | different every time |
| Issues a Vault lease? | **no** | yes |
| Connections already open when it rotates | **keep working** — the user still exists | **all break** — the user is deleted |
| What breaks | only *newly opened* connections, until the app picks up the new password | everything, at `max_ttl` |
| Read at | `database/static-creds/<role>` | `database/creds/<role>` |

The crucial property of a static role: **a password is only checked when a connection is
opened.** Changing it cannot disturb a connection that is already established. That is what
makes this pattern safe with no coordination between the credential refresh and the pool.

---

## Vault configuration required

All commands assume `VAULT_ADDR` and a token with sufficient privileges. On **Vault
Enterprise**, also `export VAULT_NAMESPACE=<your-namespace>`; on Community, omit it.

### 1. Enable the database secrets engine

```bash
vault secrets enable database
```

### 2. Configure the connection to your database

```bash
vault write database/config/YOUR_DB_CONNECTION \
    plugin_name=postgresql-database-plugin \
    allowed_roles="demo-pool-static" \
    connection_url="postgresql://{{username}}:{{password}}@YOUR_DB_HOST:5432/YOUR_DB?sslmode=disable" \
    username="YOUR_DB_ADMIN_USER" \
    password="YOUR_DB_ADMIN_PASSWORD"
```

`{{username}}` and `{{password}}` are **literal templating** — Vault substitutes them. Keep
the quotes so your shell does not expand them.

### 3. Create the database user Vault will manage

A static role takes ownership of a user that **already exists**. Create it first, with
whatever privileges your application needs:

```sql
CREATE ROLE demo_pool_static LOGIN PASSWORD 'placeholder-vault-will-replace-this';
GRANT SELECT ON demo_items TO demo_pool_static;
```

The initial password is irrelevant — Vault replaces it immediately in the next step.

### 4. Create the static role

```bash
vault write database/static-roles/demo-pool-static \
    db_name=YOUR_DB_CONNECTION \
    username=demo_pool_static \
    rotation_period=120
```

`rotation_period=120` means Vault changes the password every two minutes. That is
deliberately aggressive so a rotation is watchable during a demo; use hours or days in
production.

Verify:

```bash
vault read database/static-creds/demo-pool-static
```

You should see `username`, `password`, `rotation_period`, and a `ttl` counting down to the
next rotation. Note there is **no `lease_id`** and `lease_duration` is `0` — static roles do
not issue leases, which is why none of Spring's lease machinery applies.

### 5. Enable and configure Kubernetes auth

```bash
vault auth enable kubernetes

vault write auth/kubernetes/config \
    kubernetes_host="https://$KUBERNETES_PORT_443_TCP_ADDR:443"
```

(Run from inside the cluster, or supply the API server address explicitly.)

### 6. Create a policy granting read on that one path

```bash
vault policy write demo-pool-static-policy - <<'EOF'
path "database/static-creds/demo-pool-static" {
  capabilities = ["read"]
}
EOF
```

### 7. Bind the policy to the application's Kubernetes identity

```bash
vault write auth/kubernetes/role/demo-pool-static \
    bound_service_account_names=vault-spring-demo \
    bound_service_account_namespaces=vault-demo \
    policies=demo-pool-static-policy \
    ttl=1h
```

This says: *a pod running as the ServiceAccount `vault-spring-demo` in the namespace
`vault-demo` may log in, and receives a token carrying that policy.*

---

## Build and deploy

Replace `YOUR_REGISTRY` with your container registry.

```bash
docker build -t YOUR_REGISTRY/vault-spring-static-demo:1.0.0 .
docker push  YOUR_REGISTRY/vault-spring-static-demo:1.0.0
```

Edit `vault-spring-static-demo.yaml` — set the `image:` and the `VAULT_*` / `DB_*` values —
then:

```bash
kubectl apply -f vault-spring-static-demo.yaml
kubectl -n vault-demo port-forward svc/vault-spring-static-demo 8080:80
```

Open <http://localhost:8080>.

Building on an Apple Silicon Mac for an amd64 cluster needs `--platform linux/amd64`. Pushing
to AWS ECR also needs `--provenance=false --sbom=false`, or ECR rejects the push with a 403
on a blob HEAD request.

---

## The code

Seven files. **Two of them are the pattern**; the rest is scaffolding.

### Step 1 — How the application logs in to Vault

**`src/main/resources/application.yml`**

```yaml
spring:
  cloud:
    vault:
      uri: ${VAULT_ADDR:http://vault.vault.svc.cluster.local:8200}
      namespace: ${VAULT_NAMESPACE:}          # Enterprise only
      authentication: KUBERNETES
      kubernetes:
        role: ${VAULT_AUTH_ROLE:demo-pool-static}
        kubernetes-path: ${VAULT_AUTH_MOUNT:kubernetes}
        service-account-token-file: /var/run/secrets/kubernetes.io/serviceaccount/token
```

That last line is the heart of it. Kubernetes automatically mounts a signed token for the
pod at that path — nobody deploys it, it is part of being a pod. The library reads it, sends
it to Vault, Vault verifies it with the Kubernetes API, and returns a Vault token.

**No code was written for any of that.** It is six lines of configuration.

Note also:

```yaml
demo:
  db:
    url: ${DB_URL:jdbc:postgresql://.../postgres}    # no username, no password
```

There is nowhere in configuration for a credential to live.

### Step 2 — Something that holds the credential

**`VaultCredentialHolder.java`**

```java
private volatile Credential current;      // ← a field, not a config property

public synchronized void refresh() {
    VaultResponse response = vault.read(path);      // the only Vault call in the app
    Map<String, Object> data = response.getData();

    this.current = new Credential(
            (String) data.get("username"),
            (String) data.get("password"), ...);
}
```

A field can be **replaced while the application runs**. A configuration property cannot —
which is precisely why the conventional setup needs a restart.

`refresh()` is called from three places:

1. **at startup** (`@PostConstruct`) — we need something to connect with
2. **on a schedule** (`@Scheduled`) — optional, see below
3. **on an authentication failure** — the one that matters

`volatile` matters: the refresh happens on a background thread while Hikari's threads read
the value.

### Step 3 — The wrapper (this is the pattern)

**`VaultAwareDataSource.java`**

```java
@Override
public Connection getConnection() throws SQLException {

    var credential = holder.current();              // ask the holder, right now

    try {
        return DriverManager.getConnection(jdbcUrl,
                credential.username(), credential.password());

    } catch (SQLException e) {
        if (!isAuthenticationFailure(e)) throw e;   // database down? not our problem

        holder.refresh();                           // stale password → fetch the current one
        var fresh = holder.current();
        return DriverManager.getConnection(jdbcUrl,
                fresh.username(), fresh.password()); // retry once
    }
}
```

Three sentences:

1. Ask the holder for the credential (a memory read, microseconds)
2. Open the connection with it
3. If it was refused *because the password was stale*, refresh from Vault and try again

**When is this called?** Only when HikariCP opens a **new physical connection** — at startup,
when a connection reaches `maxLifetime`, when the pool grows under load, or after a network
interruption. It is **not** called per request; requests using an already-open connection
never come through here.

**Does this contact Vault every time?** No. `holder.current()` reads a field in memory — it
is a variable read, not a network call. Vault is contacted only by the background refresh
and, occasionally, by the retry path above.

The distinction matters, so here are real figures from this demo running for 44 hours with a
deliberately aggressive 120-second rotation and a 30-second refresh:

| | |
|---|---|
| Queries served | 157,837 |
| Calls to Vault | 5,681 — about 2 per minute |

Those calls track the **refresh schedule**, not the queries and not the connections. Opening a
connection costs roughly 70 ms; reading the credential from memory costs roughly 0.03 ms,
about 0.04% of it.

With production settings the number falls further still. A credential rotating daily with a
five-minute refresh interval is **288 calls to Vault per day, per application** — the same
whether the application serves a thousand queries or ten million. Load on Vault is decoupled
from application traffic entirely.

> This property depends on the holder. If an implementation calls Vault *directly* inside
> `getConnection()` rather than reading a cached value, the concern becomes real: a burst of
> new connections would produce a burst of calls to Vault. Keeping a holder in front of it is
> what makes the pattern cheap.

The guard on line 3 matters. "Could not connect" has many causes, and only one of them is
fixed by refreshing from Vault. Refreshing on *every* connection error would hammer Vault
whenever the database itself is down, turning one outage into two:

| Database | Authentication error |
|---|---|
| PostgreSQL | SQLSTATE `28P01` / `28000` |
| Oracle | `ORA-01017` |
| SQL Server | error `18456` |

All three are matched in `isAuthenticationFailure()`.

### Step 4 — Wiring it into HikariCP

**`DataSourceConfig.java`**

```java
HikariConfig config = new HikariConfig();

config.setDataSource(new VaultAwareDataSource(jdbcUrl, holder, events));   // ← the one line

config.setMaximumPoolSize(poolSize);
config.setMinimumIdle(poolSize);
config.setMaxLifetime(maxLifetimeMs);
```

Instead of being handed a password, Hikari is handed an object it can ask.

Note what is **absent**: no `setUsername()`, no `setPassword()`. Everything else is ordinary
pool configuration, unchanged from any Spring Boot application.

`setMaxLifetime` is **not required** for static roles — the database user is never deleted,
so open connections stay valid indefinitely. It is here for two reasons: it makes the pool
gradually converge on the newest password (Hikari staggers these retirements, so it behaves
like a rolling replacement), and it becomes **mandatory** — set below `max_ttl` — if this is
ever pointed at a dynamic role.

### The remaining files

| File | Purpose |
|---|---|
| `Application.java` | Spring Boot entry point, `@EnableScheduling` |
| `DemoService.java` | a query once a second, so a rotation is visible rather than theoretical |
| `DemoController.java` | `/` and `/api/status` |
| `Page.java` | the single HTML page, inlined so the image needs no static resources |
| `DemoEvents.java` | a short in-memory log shown on the page |

---

## How it behaves

```
POD STARTS
  ├─ Kubernetes mounts a token into the pod
  ├─ Spring Cloud Vault sends it to Vault → receives a Vault token
  ├─ holder reads database/static-creds/... → holds username + password
  └─ Hikari opens 5 connections, each asking the wrapper

STEADY STATE
  └─ queries reuse those 5 connections; nothing asks Vault

VAULT ROTATES (every rotation_period)
  ├─ the password changes in the database
  ├─ the 5 open connections keep working    ← the user still exists
  └─ queries keep succeeding. Nothing breaks, nothing restarts.

A CONNECTION IS REPLACED (maxLifetime, growth, network blip)
  ├─ Hikari calls the wrapper
  ├─ holder already refreshed → connects normally
  └─ holder was stale        → auth-failure → refresh → retry → connects normally
```

On the page you will see the countdown reach zero, the credential refresh count increment,
the success counter climb continuously, and the failure counter stay at **zero**. In the event
log, an `auth-failure` immediately followed by `recovered` — in the same second — is the
pattern doing its job.

> That pair of log lines is the whole answer to *"how does the application know the password
> changed?"* It does not know, and it does not need to. It finds out at the only moment that
> matters, and fixes itself.

---

## Is the scheduled refresh necessary?

**No.** The minimum that works is:

```
at startup       → read the credential once
on auth failure  → refresh, retry once
```

With only those two, a rotation costs exactly **one failed connection attempt**, recovered in
place. No outage, no restart.

The scheduled refresh is worth having for operational reasons rather than correctness:

- without it, every rotation puts an authentication error in the log — which trains people to
  ignore authentication errors
- the failure path costs a failed connect plus a Vault call, and it lands on whatever request
  triggered it
- a scheduled read surfaces a broken policy or an unreachable Vault proactively

Set `REFRESH_INTERVAL_MS` well above `rotation_period` to watch the failure path fire on every
cycle; set it below to keep the log quiet.

---

## Configuration reference

Set in the Deployment's `env:`; defaults live in `application.yml`.

| Variable | Default | Notes |
|---|---|---|
| `VAULT_ADDR` | `http://vault.vault.svc.cluster.local:8200` | |
| `VAULT_NAMESPACE` | *(empty)* | Vault Enterprise only |
| `VAULT_AUTH_MOUNT` | `kubernetes` | path the Kubernetes auth method is enabled at |
| `VAULT_AUTH_ROLE` | `demo-pool-static` | Vault role bound to the ServiceAccount |
| `VAULT_STATIC_PATH` | `database/static-creds/demo-pool-static` | the credential path |
| `DB_URL` | a PostgreSQL JDBC URL | **must contain no credentials** |
| `DB_QUERY` | `SELECT count(*) FROM demo_items` | the workload query |
| `POOL_SIZE` | `5` | physical connections |
| `POOL_MAX_LIFETIME_MS` | `120000` | optional for static roles; mandatory below `max_ttl` for dynamic |
| `REFRESH_INTERVAL_MS` | `30000` | scheduled refresh; see above |
| `WORKLOAD_INTERVAL_MS` | `1000` | one query per second |

---

## Porting to Oracle or SQL Server

The two classes do not change. Three things do:

1. **Driver and JDBC URL** — add the Oracle or MSSQL driver to `pom.xml`.
2. **The authentication-error check** — already handles all three (`ORA-01017`, `18456`,
   `28P01`).
3. **The Vault plugin** — `oracle-database-plugin` is a separate install; PostgreSQL and
   SQL Server are built in.

Nothing else differs, because from the application's side a Vault path returning a username
and a password looks identical regardless of the database behind it.

**One important difference between platforms.** Oracle supports *gradual password rollover*,
where the old and new passwords are both valid for a configured window. PostgreSQL and SQL
Server have **no equivalent** — the old password stops working the instant it is changed.

This is an argument *for* this pattern rather than against it: an approach that depends on a
rollover window only works on Oracle, whereas fetching the credential as each connection
opens behaves identically on all three. Where Oracle's rollover window exists, it becomes an
extra safety net rather than the mechanism.

---

## Security notes

- The database credential is **never written to disk** and never appears in
  `application.yml`, environment variables, or the pool's configuration. It exists only in
  memory, and only between a refresh and the next rotation.
- The application holds **no Vault token in configuration**. It authenticates with the
  Kubernetes identity the platform gives it.
- The Vault policy grants `read` on exactly one path. The application cannot read any other
  secret.
- `/api/status` deliberately performs **no database query**. It is the readiness and liveness
  probe target, and a probe that queried the database would restart the pod at precisely the
  moment a credential problem was being demonstrated.

package com.hashicorp.demo;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

import java.util.Map;

/**
 * Holds the current database credential in memory.
 *
 * This is the piece that replaces "password read once at startup and
 * copied into the pool". Nothing here is cached beyond the current
 * value, and the value can be replaced while the application runs.
 *
 * Note what this class does NOT do: it never touches the connection
 * pool. It only keeps a username and password current. The pool reads
 * from it; the two never coordinate.
 */
@Component
public class VaultCredentialHolder {

    private static final Logger log = LoggerFactory.getLogger(VaultCredentialHolder.class);

    /** A credential, plus when we fetched it and when Vault rotates next. */
    public record Credential(String username, String password, long fetchedAtMs, long ttlSeconds) {}

    private final VaultTemplate vault;
    private final String path;

    /** volatile: written by the refresh thread, read by Hikari's threads. */
    private volatile Credential current;

    private volatile int refreshCount = 0;

    public VaultCredentialHolder(
            VaultTemplate vault,
            @Value("${demo.vault.static-creds-path}") String path) {
        this.vault = vault;
        this.path = path;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * Read the current credential from Vault and replace what we hold.
     *
     * Called three times: once at startup, on a schedule, and — the
     * important one — whenever opening a connection fails because the
     * password we held was stale.
     */
    public synchronized void refresh() {

        VaultResponse response = vault.read(path);

        if (response == null || response.getData() == null) {
            throw new IllegalStateException("Vault returned nothing for " + path);
        }

        Map<String, Object> data = response.getData();

        // A Vault STATIC role returns the ttl as seconds until the next
        // scheduled rotation. There is no lease and no max_ttl, which is
        // why none of Spring's lease machinery applies here.
        long ttl = data.get("ttl") == null
                ? 0L
                : ((Number) data.get("ttl")).longValue();

        this.current = new Credential(
                (String) data.get("username"),
                (String) data.get("password"),
                System.currentTimeMillis(),
                ttl);

        this.refreshCount++;

        log.info("credential refreshed from Vault: user={} ttl={}s", current.username(), ttl);
    }

    /**
     * Scheduled refresh.
     *
     * Strictly optional: the refresh-on-authentication-failure path in
     * VaultAwareDataSource is what makes rotation survivable. This just
     * keeps that failure path from being the normal path, so a rotation
     * does not put an authentication error in the log every time.
     */
    @Scheduled(fixedDelayString = "${demo.refresh-interval-ms}")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (Exception e) {
            // Deliberately swallowed: we still hold a working credential,
            // and the failure path will recover if it turns out we do not.
            log.warn("scheduled refresh failed ({}), keeping the current credential", e.getMessage());
        }
    }

    public Credential current() {
        return current;
    }

    public int refreshCount() {
        return refreshCount;
    }
}

package com.hashicorp.demo;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small steady workload, so a rotation is visible rather than theoretical.
 *
 * Without traffic there is nothing on screen to watch: the interesting
 * moment is that these counters keep climbing while Vault rotates the
 * password underneath the pool.
 */
@Service
public class DemoService {

    private final DataSource dataSource;
    private final VaultCredentialHolder holder;
    private final DemoEvents events;
    private final String query;

    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile String lastError = null;

    public DemoService(DataSource dataSource,
                       VaultCredentialHolder holder,
                       DemoEvents events,
                       @Value("${demo.db.query}") String query) {
        this.dataSource = dataSource;
        this.holder = holder;
        this.events = events;
        this.query = query;
    }

    /** Runs a real query against the database, using a pooled connection. */
    @Scheduled(fixedDelayString = "${demo.workload-interval-ms}")
    public void runQuery() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(query)) {

            rs.next();
            ok.incrementAndGet();

        } catch (Exception e) {
            failed.incrementAndGet();
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            events.log("query-failed", lastError);
        }
    }

    public Map<String, Object> status() {

        HikariDataSource hikari = (HikariDataSource) dataSource;

        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("total", hikari.getHikariPoolMXBean().getTotalConnections());
        pool.put("active", hikari.getHikariPoolMXBean().getActiveConnections());
        pool.put("idle", hikari.getHikariPoolMXBean().getIdleConnections());
        pool.put("maxLifetimeMs", hikari.getMaxLifetime());

        VaultCredentialHolder.Credential credential = holder.current();

        // Vault returns ttl as "seconds until the next rotation" at the
        // moment we read it, so it has to be counted down from there --
        // otherwise the page shows a frozen number.
        long elapsed = (System.currentTimeMillis() - credential.fetchedAtMs()) / 1000;
        long remaining = Math.max(0, credential.ttlSeconds() - elapsed);

        Map<String, Object> cred = new LinkedHashMap<>();
        cred.put("username", credential.username());
        cred.put("ttlSeconds", credential.ttlSeconds());
        cred.put("ttlRemainingSeconds", remaining);
        cred.put("fetchedSecondsAgo", (System.currentTimeMillis() - credential.fetchedAtMs()) / 1000);
        cred.put("refreshCount", holder.refreshCount());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credential", cred);
        out.put("pool", pool);
        out.put("queriesOk", ok.get());
        out.put("queriesFailed", failed.get());
        out.put("lastError", lastError);
        out.put("events", events.recent());
        return out;
    }
}

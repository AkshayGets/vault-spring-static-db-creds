package com.hashicorp.demo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires the wrapper into HikariCP.
 *
 * The one line that matters is setDataSource(...). Everything else is
 * ordinary pool configuration, unchanged from what any Spring Boot app
 * already has.
 *
 * Notice what is absent: no setUsername(), no setPassword(). There is
 * nowhere for a credential to be stored, which is the point.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(
            VaultCredentialHolder holder,
            DemoEvents events,
            @Value("${demo.db.url}") String jdbcUrl,
            @Value("${demo.pool.size}") int poolSize,
            @Value("${demo.pool.max-lifetime-ms}") long maxLifetimeMs) {

        HikariConfig config = new HikariConfig();

        // Hikari asks this object for each new physical connection,
        // instead of being handed a password once at startup.
        config.setDataSource(new VaultAwareDataSource(jdbcUrl, holder, events));

        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize);

        // Not required for static roles -- the database user is never
        // deleted, so open connections stay valid indefinitely. It is
        // here because it is what makes the pool gradually converge on
        // the newest password, and because it becomes MANDATORY (below
        // max_ttl) if this is ever pointed at a dynamic role.
        config.setMaxLifetime(maxLifetimeMs);

        config.setPoolName("vault-pool");

        events.log("startup", "Hikari pool created with " + poolSize
                + " connections, maxLifetime " + (maxLifetimeMs / 1000) + "s");

        return new HikariDataSource(config);
    }
}

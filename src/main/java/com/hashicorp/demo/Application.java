package com.hashicorp.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Vault static database roles + HikariCP, without restarts.
 *
 * THE PATTERN IS TWO CLASSES. Everything else is demo scaffolding.
 *
 *   VaultCredentialHolder   holds the current credential in memory,
 *                           and can replace it while the app runs
 *   VaultAwareDataSource    hands it to Hikari as each new physical
 *                           connection opens, and refreshes + retries
 *                           if the password turned out to be stale
 *
 *   DataSourceConfig        wires the wrapper into HikariCP (one line)
 *   DemoService             a steady workload, so a rotation is visible
 *   DemoController, Page,   the page and its JSON endpoints
 *   DemoEvents
 */
@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

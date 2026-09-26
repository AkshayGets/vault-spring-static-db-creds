package com.hashicorp.demo;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * THE WRAPPER. This is the whole pattern, in one method.
 *
 * Normally Spring hands HikariCP a username and a password, and Hikari
 * keeps that copy for the life of the process. That copy is what goes
 * stale when Vault rotates, and why a restart is needed today.
 *
 * Instead we hand Hikari this object. When Hikari needs a NEW physical
 * connection it calls getConnection() below, and we fetch the current
 * credential at that moment. There is no stored password anywhere, so
 * there is nothing that can be stale.
 *
 * Note this is NOT called per request. Requests borrowing an already
 * open connection never come through here. It runs only when the pool
 * opens a new physical connection: at startup, on maxLifetime recycle,
 * when the pool grows, or after a network blip.
 */
public class VaultAwareDataSource extends AbstractDataSource {

    private final String jdbcUrl;
    private final VaultCredentialHolder holder;
    private final DemoEvents events;

    public VaultAwareDataSource(String jdbcUrl, VaultCredentialHolder holder, DemoEvents events) {
        this.jdbcUrl = jdbcUrl;
        this.holder = holder;
        this.events = events;
    }

    @Override
    public Connection getConnection() throws SQLException {

        VaultCredentialHolder.Credential credential = holder.current();

        try {
            return DriverManager.getConnection(
                    jdbcUrl, credential.username(), credential.password());

        } catch (SQLException e) {

            // Anything other than "wrong password" is a real problem --
            // the database being down, the network being gone -- and
            // refreshing from Vault would not help. Only retry for the
            // one error that a stale password actually produces.
            if (!isAuthenticationFailure(e)) {
                throw e;
            }

            // The password we held was stale: Vault rotated and we had
            // not picked it up yet. Go and get the current one, then
            // try once more. This is what makes a rotation survivable
            // without a restart and without perfect timing.
            events.log("auth-failure",
                    "connection refused with a stale password -- refreshing from Vault");

            holder.refresh();

            VaultCredentialHolder.Credential fresh = holder.current();

            Connection connection = DriverManager.getConnection(
                    jdbcUrl, fresh.username(), fresh.password());

            events.log("recovered",
                    "retry succeeded using the rotated password (" + fresh.username() + ")");

            return connection;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        // Hikari does not use this path when a DataSource is supplied,
        // but DataSource requires it.
        return getConnection();
    }

    /**
     * Is this specifically an authentication failure?
     *
     * PostgreSQL : SQLSTATE 28P01 (invalid_password) / 28000
     * Oracle     : ORA-01017
     * SQL Server : error 18456
     *
     * Getting this wrong matters: refreshing on EVERY connection error
     * would hammer Vault whenever the database itself is down, turning
     * one outage into two.
     */
    private boolean isAuthenticationFailure(SQLException e) {

        String sqlState = e.getSQLState();

        if ("28P01".equals(sqlState) || "28000".equals(sqlState)) {
            return true;
        }

        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();

        return message.contains("password authentication failed")
                || message.contains("ora-01017")
                || message.contains("login failed for user");
    }
}

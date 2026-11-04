package com.bookscreen.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class DatabaseConfig implements AutoCloseable {
    private final HikariDataSource dataSource;

    public DatabaseConfig(AppConfig appConfig, boolean runMigrations) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(appConfig.jdbcUrl());
        hc.setUsername(appConfig.dbUser());
        hc.setPassword(appConfig.dbPassword());
        hc.setMaximumPoolSize(10);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(10_000);
        // Section 7.3: Writer connections use READ COMMITTED to reduce gap locking
        hc.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(hc);

        if (runMigrations) {
            Flyway.configure()
                    .dataSource(this.dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Borrows a connection for transactional writes with autoCommit disabled (Section 7.1).
     */
    public Connection openTransactionalConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return conn;
    }

    /**
     * Borrows a separate auto-commit connection for sync_runs / sync_errors logging (Section 7.1).
     */
    public Connection openAutoCommitConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(true);
        return conn;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
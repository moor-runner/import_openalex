package com.clio.openalex.importer.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

final class JdbcConnections {
    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/openalex";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASSWORD = "1234";

    private JdbcConnections() {
    }

    static Connection open() throws SQLException {
        return DriverManager.getConnection(
                setting("openalex.db.url", "OPENALEX_DB_URL", DEFAULT_URL),
                setting("openalex.db.user", "OPENALEX_DB_USER", DEFAULT_USER),
                setting("openalex.db.password", "OPENALEX_DB_PASSWORD", DEFAULT_PASSWORD));
    }

    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    @FunctionalInterface
    interface ConnectionProvider {
        Connection open() throws SQLException;
    }
}

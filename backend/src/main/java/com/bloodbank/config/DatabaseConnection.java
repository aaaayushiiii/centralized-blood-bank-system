package com.bloodbank.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * JDBC Connection Factory for Oracle Database.
 * Configured with parameters matching standard VIT Oracle XE / 19c setups.
 */
public class DatabaseConnection {

    // Default Oracle XE Configuration
    private static final String DEFAULT_URL = "jdbc:oracle:thin:@localhost:1521:XE";
    private static final String DEFAULT_USER = "SYSTEM";
    private static final String DEFAULT_PASS = "oracle";
    private static final String DRIVER_CLASS = "oracle.jdbc.OracleDriver";

    private static String dbUrl;
    private static String dbUser;
    private static String dbPass;
    private static boolean driverLoaded = false;
    private static Boolean connectionHealthy = null;

    static {
        dbUrl = System.getenv().getOrDefault("ORACLE_URL", DEFAULT_URL);
        dbUser = System.getenv().getOrDefault("ORACLE_USER", DEFAULT_USER);
        dbPass = System.getenv().getOrDefault("ORACLE_PASSWORD", DEFAULT_PASS);

        try {
            Class.forName(DRIVER_CLASS);
            driverLoaded = true;
            System.out.println("[DatabaseConnection] Oracle JDBC Driver successfully loaded: " + DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            System.err.println("[DatabaseConnection] Notice: Oracle JDBC driver not on classpath. Falling back to active InMemory/Simulation Store.");
            driverLoaded = false;
        }
    }

    public static Connection getConnection() throws SQLException {
        if (!driverLoaded) {
            throw new SQLException("Oracle JDBC Driver not loaded");
        }
        return DriverManager.getConnection(dbUrl, dbUser, dbPass);
    }

    public static boolean isConnected() {
        if (!driverLoaded) return false;
        try (Connection conn = getConnection()) {
            connectionHealthy = conn != null && !conn.isClosed();
            return connectionHealthy;
        } catch (Exception e) {
            connectionHealthy = false;
            return false;
        }
    }

    public static String getDbUrl() { return dbUrl; }
    public static String getDbUser() { return dbUser; }
}

package com.bloodbank.dao;

import com.bloodbank.config.DatabaseConnection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication Data Access Object.
 * Handles Citizen & Hospital Staff sign-up, login, and hospital-scoped staff permissions.
 */
public class AuthDao {

    // In-Memory store fallback for immediate testing & demos if Oracle DB is offline
    private static final Map<String, Map<String, Object>> IN_MEMORY_USERS = new ConcurrentHashMap<>();
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>();

    static {
        // Seed default Citizen: citizen@demo.com / password123
        Map<String, Object> cUser = new LinkedHashMap<>();
        cUser.put("user_id", "USR-001");
        cUser.put("email", "citizen@demo.com");
        cUser.put("password_hash", hashPassword("password123"));
        cUser.put("role", "CITIZEN");
        cUser.put("full_name", "Ananya Sharma");
        cUser.put("phone", "+919876543210");
        cUser.put("hospital_id", null);
        IN_MEMORY_USERS.put("citizen@demo.com", cUser);

        // Seed default Hospital Staff: staff.central@cityhospital.org / password123
        Map<String, Object> sUser1 = new LinkedHashMap<>();
        sUser1.put("user_id", "USR-002");
        sUser1.put("email", "staff.central@cityhospital.org");
        sUser1.put("password_hash", hashPassword("password123"));
        sUser1.put("role", "HOSPITAL_STAFF");
        sUser1.put("full_name", "Dr. Ramesh Kumar");
        sUser1.put("phone", "+919811223344");
        sUser1.put("hospital_id", "HOSP-01");
        sUser1.put("hospital_name", "City Central Blood Bank & Hospital");
        IN_MEMORY_USERS.put("staff.central@cityhospital.org", sUser1);

        // Seed second Hospital Staff: staff.stjude@stjude.org / password123
        Map<String, Object> sUser2 = new LinkedHashMap<>();
        sUser2.put("user_id", "USR-003");
        sUser2.put("email", "staff.stjude@stjude.org");
        sUser2.put("password_hash", hashPassword("password123"));
        sUser2.put("role", "HOSPITAL_STAFF");
        sUser2.put("full_name", "Sister Mary Varghese");
        sUser2.put("phone", "+919822334455");
        sUser2.put("hospital_id", "HOSP-02");
        sUser2.put("hospital_name", "St. Jude Care Multispecialty");
        IN_MEMORY_USERS.put("staff.stjude@stjude.org", sUser2);
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString(password.hashCode());
        }
    }

    public Map<String, Object> authenticate(String email, String password) {
        String hashed = hashPassword(password);

        // 1. Try Oracle Database query first if connected
        if (DatabaseConnection.isConnected()) {
            String sql = "SELECT u.user_id, u.email, u.password_hash, u.role, u.full_name, u.phone, " +
                         "       s.hospital_id, h.name as hospital_name " +
                         "FROM USERS u " +
                         "LEFT JOIN HOSPITAL_STAFF s ON u.user_id = s.user_id " +
                         "LEFT JOIN HOSPITALS h ON s.hospital_id = h.hospital_id " +
                         "WHERE LOWER(u.email) = LOWER(?)";
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, email);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String storedHash = rs.getString("password_hash");
                        if (storedHash.equalsIgnoreCase(hashed)) {
                            Map<String, Object> user = new LinkedHashMap<>();
                            user.put("user_id", rs.getString("user_id"));
                            user.put("email", rs.getString("email"));
                            user.put("role", rs.getString("role"));
                            user.put("full_name", rs.getString("full_name"));
                            user.put("phone", rs.getString("phone"));
                            user.put("hospital_id", rs.getString("hospital_id"));
                            user.put("hospital_name", rs.getString("hospital_name"));
                            String token = "TOKEN-" + UUID.randomUUID().toString();
                            user.put("token", token);
                            SESSIONS.put(token, (String) user.get("user_id"));
                            return user;
                        }
                    }
                }
            } catch (SQLException e) {
                System.err.println("[AuthDao] Oracle query failed, falling back to memory store: " + e.getMessage());
            }
        }

        // 2. In-Memory fallback store
        Map<String, Object> user = IN_MEMORY_USERS.get(email.toLowerCase().trim());
        if (user != null) {
            String storedHash = (String) user.get("password_hash");
            if (storedHash.equalsIgnoreCase(hashed)) {
                Map<String, Object> res = new LinkedHashMap<>(user);
                res.remove("password_hash");
                String token = "TOKEN-" + UUID.randomUUID().toString();
                res.put("token", token);
                SESSIONS.put(token, (String) res.get("user_id"));
                return res;
            }
        }
        return null; // Invalid credentials
    }

    public Map<String, Object> registerCitizen(String email, String password, String fullName, String phone) throws Exception {
        String cleanEmail = email.toLowerCase().trim();
        if (IN_MEMORY_USERS.containsKey(cleanEmail)) {
            throw new Exception("Email already registered: " + email);
        }

        String userId = "USR-" + (100 + IN_MEMORY_USERS.size() + 1);
        String hashed = hashPassword(password);

        if (DatabaseConnection.isConnected()) {
            String sql = "INSERT INTO USERS (user_id, email, password_hash, role, full_name, phone) VALUES (?, ?, ?, 'CITIZEN', ?, ?)";
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, cleanEmail);
                stmt.setString(3, hashed);
                stmt.setString(4, fullName);
                stmt.setString(5, phone);
                stmt.executeUpdate();
            } catch (SQLException e) {
                System.err.println("[AuthDao] SQL Insert error: " + e.getMessage());
            }
        }

        Map<String, Object> newUser = new LinkedHashMap<>();
        newUser.put("user_id", userId);
        newUser.put("email", cleanEmail);
        newUser.put("password_hash", hashed);
        newUser.put("role", "CITIZEN");
        newUser.put("full_name", fullName);
        newUser.put("phone", phone);
        newUser.put("hospital_id", null);
        IN_MEMORY_USERS.put(cleanEmail, newUser);

        Map<String, Object> res = new LinkedHashMap<>(newUser);
        res.remove("password_hash");
        res.put("token", "TOKEN-" + UUID.randomUUID().toString());
        return res;
    }

    public Map<String, Object> registerHospitalStaff(String email, String password, String fullName, String phone, String hospitalId) throws Exception {
        String cleanEmail = email.toLowerCase().trim();
        if (IN_MEMORY_USERS.containsKey(cleanEmail)) {
            throw new Exception("Email already registered: " + email);
        }

        String userId = "USR-" + (100 + IN_MEMORY_USERS.size() + 1);
        String staffId = "STF-" + (100 + IN_MEMORY_USERS.size() + 1);
        String hashed = hashPassword(password);

        if (DatabaseConnection.isConnected()) {
            Connection conn = null;
            try {
                conn = DatabaseConnection.getConnection();
                conn.setAutoCommit(false);
                String sql1 = "INSERT INTO USERS (user_id, email, password_hash, role, full_name, phone) VALUES (?, ?, ?, 'HOSPITAL_STAFF', ?, ?)";
                try (PreparedStatement stmt1 = conn.prepareStatement(sql1)) {
                    stmt1.setString(1, userId);
                    stmt1.setString(2, cleanEmail);
                    stmt1.setString(3, hashed);
                    stmt1.setString(4, fullName);
                    stmt1.setString(5, phone);
                    stmt1.executeUpdate();
                }

                String sql2 = "INSERT INTO HOSPITAL_STAFF (staff_id, user_id, hospital_id) VALUES (?, ?, ?)";
                try (PreparedStatement stmt2 = conn.prepareStatement(sql2)) {
                    stmt2.setString(1, staffId);
                    stmt2.setString(2, userId);
                    stmt2.setString(3, hospitalId);
                    stmt2.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                if (conn != null) conn.rollback();
                throw e;
            } finally {
                if (conn != null) conn.setAutoCommit(true);
            }
        }

        Map<String, Object> newUser = new LinkedHashMap<>();
        newUser.put("user_id", userId);
        newUser.put("email", cleanEmail);
        newUser.put("password_hash", hashed);
        newUser.put("role", "HOSPITAL_STAFF");
        newUser.put("full_name", fullName);
        newUser.put("phone", phone);
        newUser.put("hospital_id", hospitalId);
        IN_MEMORY_USERS.put(cleanEmail, newUser);

        Map<String, Object> res = new LinkedHashMap<>(newUser);
        res.remove("password_hash");
        res.put("token", "TOKEN-" + UUID.randomUUID().toString());
        return res;
    }
}

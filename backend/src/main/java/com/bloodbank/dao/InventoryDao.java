package com.bloodbank.dao;

import com.bloodbank.config.DatabaseConnection;

import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Inventory Data Access Object.
 * Returns available units, blood groups, expiry dates, and hospital details.
 * Scopes data strictly to hospitalId when requested by hospital staff.
 */
public class InventoryDao {

    // In-memory seed inventory for instant testing & offline demos
    private static final List<Map<String, Object>> SEED_BAGS = new ArrayList<>();

    static {
        LocalDate today = LocalDate.now();

        // City Central (HOSP-01) Units
        addSeedBag("BAG-101", "O", "+", today.minusDays(10), today.plusDays(32), "AVAILABLE", "HOSP-01", "City Central Blood Bank & Hospital", "+918023456789", "D-01");
        addSeedBag("BAG-102", "O", "-", today.minusDays(20), today.plusDays(22), "AVAILABLE", "HOSP-01", "City Central Blood Bank & Hospital", "+918023456789", "D-02");
        addSeedBag("BAG-103", "A", "+", today.minusDays(38), today.plusDays(4), "AVAILABLE", "HOSP-01", "City Central Blood Bank & Hospital", "+918023456789", "D-03"); // Expiring soon!
        addSeedBag("BAG-104", "A", "-", today.minusDays(5), today.plusDays(37), "AVAILABLE", "HOSP-01", "City Central Blood Bank & Hospital", "+918023456789", "D-04");
        addSeedBag("BAG-105", "B", "+", today.minusDays(15), today.plusDays(27), "AVAILABLE", "HOSP-01", "City Central Blood Bank & Hospital", "+918023456789", "D-05");
        addSeedBag("BAG-106", "B", "-", today.minusDays(12), today.plusDays(30), "AVAILABLE", "HOSP-01", "City Central Blood Bank & Hospital", "+918023456789", "D-06");
        addSeedBag("BAG-107", "AB", "+", today.minusDays(8), today.plusDays(34), "AVAILABLE", "HOSP-01", "City Central Blood Bank & Hospital", "+918023456789", "D-07");
        addSeedBag("BAG-108", "AB", "-", today.minusDays(40), today.plusDays(2), "AVAILABLE", "HOSP-01", "City Central Blood Bank & Hospital", "+918023456789", "D-08"); // Expiring soon!

        // St. Jude Care (HOSP-02) Units
        addSeedBag("BAG-201", "B", "+", today.minusDays(14), today.plusDays(28), "AVAILABLE", "HOSP-02", "St. Jude Care Multispecialty", "+918098765432", "D-09");
        addSeedBag("BAG-202", "AB", "-", today.minusDays(18), today.plusDays(24), "AVAILABLE", "HOSP-02", "St. Jude Care Multispecialty", "+918098765432", "D-10");
        addSeedBag("BAG-203", "O", "+", today.minusDays(2), today.plusDays(40), "AVAILABLE", "HOSP-02", "St. Jude Care Multispecialty", "+918098765432", "D-11");
        addSeedBag("BAG-204", "O", "-", today.minusDays(39), today.plusDays(3), "AVAILABLE", "HOSP-02", "St. Jude Care Multispecialty", "+918098765432", "D-12"); // Expiring soon!

        // Apex Trauma (HOSP-03) Units
        addSeedBag("BAG-301", "O", "-", today.minusDays(5), today.plusDays(37), "AVAILABLE", "HOSP-03", "Apex Trauma & Emergency Care", "+918012344321", "D-13");
        addSeedBag("BAG-302", "A", "+", today.minusDays(10), today.plusDays(32), "AVAILABLE", "HOSP-03", "Apex Trauma & Emergency Care", "+918012344321", "D-14");
    }

    private static void addSeedBag(String bagId, String bg, String rh, LocalDate col, LocalDate exp, String status, String hId, String hName, String hPhone, String dId) {
        Map<String, Object> bag = new LinkedHashMap<>();
        bag.put("bag_id", bagId);
        bag.put("blood_group", bg);
        bag.put("rh_factor", rh);
        bag.put("blood_type", bg + rh);
        bag.put("collection_date", col.toString());
        bag.put("expiry_date", exp.toString());
        bag.put("days_to_expiry", ChronoUnit.DAYS.between(LocalDate.now(), exp));
        bag.put("status", status);
        bag.put("hospital_id", hId);
        bag.put("hospital_name", hName);
        bag.put("hospital_phone", hPhone);
        bag.put("donor_id", dId);
        SEED_BAGS.add(bag);
    }

    public Map<String, Object> getInventoryDashboard(String filterHospitalId) {
        List<Map<String, Object>> matchingBags = new ArrayList<>();

        // 1. Check Oracle DB if connected
        if (DatabaseConnection.isConnected()) {
            StringBuilder sql = new StringBuilder(
                "SELECT b.bag_id, b.blood_group, b.rh_factor, b.collection_date, b.expiry_date, b.status, " +
                "       b.hospital_id, h.name as hospital_name, h.phone as hospital_phone, b.donor_id, " +
                "       ROUND(b.expiry_date - TRUNC(SYSDATE)) as days_to_expiry " +
                "FROM BLOOD_BAGS b " +
                "JOIN HOSPITALS h ON b.hospital_id = h.hospital_id "
            );
            if (filterHospitalId != null && !filterHospitalId.trim().isEmpty()) {
                sql.append("WHERE b.hospital_id = ? ");
            }
            sql.append("ORDER BY b.expiry_date ASC");

            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                if (filterHospitalId != null && !filterHospitalId.trim().isEmpty()) {
                    stmt.setString(1, filterHospitalId.trim());
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> bag = new LinkedHashMap<>();
                        bag.put("bag_id", rs.getString("bag_id"));
                        bag.put("blood_group", rs.getString("blood_group"));
                        bag.put("rh_factor", rs.getString("rh_factor"));
                        bag.put("blood_type", rs.getString("blood_group") + rs.getString("rh_factor"));
                        bag.put("collection_date", rs.getDate("collection_date") != null ? rs.getDate("collection_date").toString() : "");
                        bag.put("expiry_date", rs.getDate("expiry_date") != null ? rs.getDate("expiry_date").toString() : "");
                        bag.put("days_to_expiry", rs.getInt("days_to_expiry"));
                        bag.put("status", rs.getString("status"));
                        bag.put("hospital_id", rs.getString("hospital_id"));
                        bag.put("hospital_name", rs.getString("hospital_name"));
                        bag.put("hospital_phone", rs.getString("hospital_phone"));
                        bag.put("donor_id", rs.getString("donor_id"));
                        matchingBags.add(bag);
                    }
                }
            } catch (SQLException e) {
                System.err.println("[InventoryDao] Oracle query error: " + e.getMessage());
            }
        }

        // 2. Fallback to memory store if DB empty or unavailable
        if (matchingBags.isEmpty()) {
            for (Map<String, Object> bag : SEED_BAGS) {
                if (filterHospitalId == null || filterHospitalId.trim().isEmpty() ||
                    filterHospitalId.equalsIgnoreCase((String) bag.get("hospital_id"))) {
                    matchingBags.add(new LinkedHashMap<>(bag));
                }
            }
        }

        // 3. Compute Aggregated Metrics
        int totalUnits = matchingBags.size();
        int availableUnits = 0;
        int reservedUnits = 0;
        int expiringSoon = 0;
        Map<String, Integer> groupCounts = new LinkedHashMap<>();

        // Initialize 8 standard blood groups with 0
        String[] groups = {"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};
        for (String g : groups) groupCounts.put(g, 0);

        for (Map<String, Object> bag : matchingBags) {
            String status = (String) bag.get("status");
            String bType = (String) bag.get("blood_type");
            long dte = ((Number) bag.get("days_to_expiry")).longValue();

            if ("AVAILABLE".equalsIgnoreCase(status)) {
                availableUnits++;
                groupCounts.put(bType, groupCounts.getOrDefault(bType, 0) + 1);
                if (dte <= 7 && dte >= 0) {
                    expiringSoon++;
                }
            } else if ("RESERVED".equalsIgnoreCase(status)) {
                reservedUnits++;
            }
        }

        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("filter_hospital_id", filterHospitalId);
        responseData.put("total_units", totalUnits);
        responseData.put("available_units", availableUnits);
        responseData.put("reserved_units", reservedUnits);
        responseData.put("expiring_within_7_days", expiringSoon);
        responseData.put("group_breakdown", groupCounts);
        responseData.put("inventory_items", matchingBags);

        return responseData;
    }
}

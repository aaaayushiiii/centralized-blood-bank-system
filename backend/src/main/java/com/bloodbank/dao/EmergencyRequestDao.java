package com.bloodbank.dao;

import com.bloodbank.config.DatabaseConnection;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Emergency Request Data Access Object.
 * Executes the 4-Table Atomic FEFO (First-Expiring, First-Out) matching transaction across:
 * 1. REQUESTS (logs incoming demand)
 * 2. BLOOD_BAGS (locks selected units and transitions status to RESERVED)
 * 3. ALLOCATIONS (creates M:N junction links with timestamp)
 * 4. BAG_STATUS_LOG (appends immutable audit trail for hemovigilance)
 */
public class EmergencyRequestDao {

    private static final List<Map<String, Object>> IN_MEMORY_REQUESTS = new ArrayList<>();
    private static final List<Map<String, Object>> IN_MEMORY_ALLOCATIONS = new ArrayList<>();
    private static final List<Map<String, Object>> IN_MEMORY_STATUS_LOGS = new ArrayList<>();

    public Map<String, Object> createEmergencyRequest(String hospitalId, String bloodGroup, String rhFactor, int unitsRequested, String priority) throws Exception {
        if (hospitalId == null || hospitalId.trim().isEmpty()) {
            hospitalId = "HOSP-01"; // Default hospital
        }
        if (unitsRequested <= 0) {
            throw new IllegalArgumentException("units_requested must be greater than 0");
        }
        if (priority == null || priority.trim().isEmpty()) {
            priority = "URGENT";
        }

        String requestId = "REQ-" + (System.currentTimeMillis() % 100000);
        List<String> allocatedBagIds = new ArrayList<>();
        String finalStatus = "PENDING";
        String message = "";

        // 1. Execute against Oracle Database if connected
        if (DatabaseConnection.isConnected()) {
            Connection conn = null;
            try {
                conn = DatabaseConnection.getConnection();

                // First attempt: Try Calling the PL/SQL Stored Procedure
                try (CallableStatement cstmt = conn.prepareCall("{call PROC_ALLOCATE_EMERGENCY_BLOOD(?, ?, ?, ?, ?, ?, ?, ?, ?)}")) {
                    cstmt.setString(1, requestId);
                    cstmt.setString(2, hospitalId);
                    cstmt.setString(3, bloodGroup);
                    cstmt.setString(4, rhFactor);
                    cstmt.setInt(5, unitsRequested);
                    cstmt.setString(6, priority);
                    cstmt.registerOutParameter(7, Types.NUMERIC); // units_allocated
                    cstmt.registerOutParameter(8, Types.VARCHAR); // request_status
                    cstmt.registerOutParameter(9, Types.VARCHAR); // message
                    cstmt.execute();

                    int allocated = cstmt.getInt(7);
                    finalStatus = cstmt.getString(8);
                    message = cstmt.getString(9);

                    // Fetch allocated bag IDs for the response payload
                    String qBags = "SELECT bag_id FROM ALLOCATIONS WHERE request_id = ?";
                    try (PreparedStatement pst = conn.prepareStatement(qBags)) {
                        pst.setString(1, requestId);
                        try (ResultSet rs = pst.executeQuery()) {
                            while (rs.next()) allocatedBagIds.add(rs.getString("bag_id"));
                        }
                    }

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("request_id", requestId);
                    result.put("hospital_id", hospitalId);
                    result.put("blood_group", bloodGroup);
                    result.put("rh_factor", rhFactor);
                    result.put("units_requested", unitsRequested);
                    result.put("units_allocated", allocated);
                    result.put("priority", priority);
                    result.put("status", finalStatus);
                    result.put("allocated_bags", allocatedBagIds);
                    result.put("message", message);
                    result.put("timestamp", LocalDateTime.now().toString());
                    return result;

                } catch (SQLException procEx) {
                    System.err.println("[EmergencyRequestDao] Stored proc not found, executing pure JDBC 4-Table transaction: " + procEx.getMessage());
                }

                // Second attempt: Native JDBC Atomic 4-Table Transaction
                conn.setAutoCommit(false);

                // Step 1: Insert into REQUESTS
                String sqlReq = "INSERT INTO REQUESTS (request_id, hospital_id, req_blood_group, req_rh_factor, units_requested, priority, status, request_time) " +
                                "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', SYSTIMESTAMP)";
                try (PreparedStatement pReq = conn.prepareStatement(sqlReq)) {
                    pReq.setString(1, requestId);
                    pReq.setString(2, hospitalId);
                    pReq.setString(3, bloodGroup);
                    pReq.setString(4, rhFactor);
                    pReq.setInt(5, unitsRequested);
                    pReq.setString(6, priority);
                    pReq.executeUpdate();
                }

                // Step 2: Select matching available units in FEFO order (First Expiring First Out)
                String sqlFind = "SELECT bag_id FROM BLOOD_BAGS WHERE blood_group = ? AND rh_factor = ? " +
                                 "AND status = 'AVAILABLE' AND expiry_date >= TRUNC(SYSDATE) " +
                                 "ORDER BY expiry_date ASC";
                List<String> candidateBags = new ArrayList<>();
                try (PreparedStatement pFind = conn.prepareStatement(sqlFind)) {
                    pFind.setString(1, bloodGroup);
                    pFind.setString(2, rhFactor);
                    try (ResultSet rs = pFind.executeQuery()) {
                        while (rs.next() && candidateBags.size() < unitsRequested) {
                            candidateBags.add(rs.getString("bag_id"));
                        }
                    }
                }

                // Step 3 & 4: Update BLOOD_BAGS, insert into ALLOCATIONS and BAG_STATUS_LOG
                for (String bagId : candidateBags) {
                    String allocId = "ALC-" + (System.currentTimeMillis() % 100000) + "-" + bagId;

                    // Update BLOOD_BAGS
                    try (PreparedStatement pUpd = conn.prepareStatement("UPDATE BLOOD_BAGS SET status = 'RESERVED' WHERE bag_id = ?")) {
                        pUpd.setString(1, bagId);
                        pUpd.executeUpdate();
                    }

                    // Insert ALLOCATIONS
                    try (PreparedStatement pAlloc = conn.prepareStatement("INSERT INTO ALLOCATIONS (allocation_id, request_id, bag_id, allocated_at) VALUES (?, ?, ?, SYSTIMESTAMP)")) {
                        pAlloc.setString(1, allocId);
                        pAlloc.setString(2, requestId);
                        pAlloc.setString(3, bagId);
                        pAlloc.executeUpdate();
                    }

                    // Insert BAG_STATUS_LOG
                    try (PreparedStatement pLog = conn.prepareStatement(
                        "INSERT INTO BAG_STATUS_LOG (bag_id, old_status, new_status, changed_at, reason) " +
                        "VALUES (?, 'AVAILABLE', 'RESERVED', SYSTIMESTAMP, ?)")) {
                        pLog.setString(1, bagId);
                        pLog.setString(2, "Emergency Allocation for " + requestId + " (" + priority + ")");
                        pLog.executeUpdate();
                    }

                    allocatedBagIds.add(bagId);
                }

                // Determine final request status
                if (allocatedBagIds.size() == unitsRequested) {
                    finalStatus = "FULFILLED";
                    message = "All " + unitsRequested + " units successfully matched & reserved via FEFO.";
                } else if (!allocatedBagIds.isEmpty()) {
                    finalStatus = "PARTIALLY_FILLED";
                    message = "Partial fulfillment: " + allocatedBagIds.size() + " of " + unitsRequested + " units allocated.";
                } else {
                    finalStatus = "PENDING";
                    message = "No matching available blood units found. Request remains PENDING.";
                }

                try (PreparedStatement pStat = conn.prepareStatement("UPDATE REQUESTS SET status = ? WHERE request_id = ?")) {
                    pStat.setString(1, finalStatus);
                    pStat.setString(2, requestId);
                    pStat.executeUpdate();
                }

                conn.commit();

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request_id", requestId);
                result.put("hospital_id", hospitalId);
                result.put("blood_group", bloodGroup);
                result.put("rh_factor", rhFactor);
                result.put("units_requested", unitsRequested);
                result.put("units_allocated", allocatedBagIds.size());
                result.put("priority", priority);
                result.put("status", finalStatus);
                result.put("allocated_bags", allocatedBagIds);
                result.put("message", message);
                result.put("timestamp", LocalDateTime.now().toString());
                return result;

            } catch (SQLException ex) {
                if (conn != null) conn.rollback();
                throw ex;
            } finally {
                if (conn != null) conn.setAutoCommit(true);
            }
        }

        // 2. In-Memory Transaction Fallback
        InventoryDao invDao = new InventoryDao();
        Map<String, Object> dashboard = invDao.getInventoryDashboard(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) dashboard.get("inventory_items");

        if (items != null) {
            for (Map<String, Object> item : items) {
                if (allocatedBagIds.size() >= unitsRequested) break;
                String bg = (String) item.get("blood_group");
                String rh = (String) item.get("rh_factor");
                String st = (String) item.get("status");

                if (bloodGroup.equalsIgnoreCase(bg) && rhFactor.equalsIgnoreCase(rh) && "AVAILABLE".equalsIgnoreCase(st)) {
                    String bagId = (String) item.get("bag_id");
                    item.put("status", "RESERVED"); // update in memory
                    allocatedBagIds.add(bagId);

                    Map<String, Object> logEntry = new LinkedHashMap<>();
                    logEntry.put("bag_id", bagId);
                    logEntry.put("old_status", "AVAILABLE");
                    logEntry.put("new_status", "RESERVED");
                    logEntry.put("changed_at", LocalDateTime.now().toString());
                    logEntry.put("reason", "Allocated to emergency request " + requestId);
                    IN_MEMORY_STATUS_LOGS.add(logEntry);
                }
            }
        }

        if (allocatedBagIds.size() == unitsRequested) {
            finalStatus = "FULFILLED";
            message = "All " + unitsRequested + " units successfully allocated via FEFO matching.";
        } else if (!allocatedBagIds.isEmpty()) {
            finalStatus = "PARTIALLY_FILLED";
            message = "Partial fulfillment: " + allocatedBagIds.size() + " of " + unitsRequested + " units allocated.";
        } else {
            finalStatus = "PENDING";
            message = "Zero matching available blood bags in network. Request placed in PENDING queue.";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request_id", requestId);
        result.put("hospital_id", hospitalId);
        result.put("blood_group", bloodGroup);
        result.put("rh_factor", rhFactor);
        result.put("units_requested", unitsRequested);
        result.put("units_allocated", allocatedBagIds.size());
        result.put("priority", priority);
        result.put("status", finalStatus);
        result.put("allocated_bags", allocatedBagIds);
        result.put("message", message);
        result.put("timestamp", LocalDateTime.now().toString());
        IN_MEMORY_REQUESTS.add(result);

        return result;
    }
}

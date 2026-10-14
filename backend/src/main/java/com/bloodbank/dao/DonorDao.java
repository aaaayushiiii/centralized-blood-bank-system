package com.bloodbank.dao;

import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.util.GeoUtils;

import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Donor Registration and Blood Search Data Access Object.
 */
public class DonorDao {

    private static final List<Map<String, Object>> IN_MEMORY_DONORS = new ArrayList<>();

    static {
        Map<String, Object> d1 = new LinkedHashMap<>();
        d1.put("donor_id", "D-01");
        d1.put("full_name", "Rahul Sen");
        d1.put("blood_group", "O");
        d1.put("rh_factor", "+");
        d1.put("age", 28);
        d1.put("gender", "M");
        d1.put("contact_number", "+919876500001");
        d1.put("last_donation_date", LocalDate.now().minusDays(120).toString());
        d1.put("eligible", true);
        IN_MEMORY_DONORS.add(d1);
    }

    public Map<String, Object> registerDonor(String fullName, String bloodGroup, String rhFactor, int age, String gender, String contact, String lastDonationDate) throws Exception {
        if (age < 18 || age > 65) {
            throw new IllegalArgumentException("Donor age must be between 18 and 65 years for medical eligibility.");
        }
        if (contact == null || contact.trim().isEmpty()) {
            throw new IllegalArgumentException("Contact number is required.");
        }

        String donorId = "D-" + (100 + IN_MEMORY_DONORS.size() + 1);
        boolean isEligible = true;
        if (lastDonationDate != null && !lastDonationDate.trim().isEmpty()) {
            try {
                LocalDate ldd = LocalDate.parse(lastDonationDate.trim());
                long daysSince = ChronoUnit.DAYS.between(ldd, LocalDate.now());
                if (daysSince < 90) {
                    isEligible = false;
                }
            } catch (Exception ignored) {}
        }

        if (DatabaseConnection.isConnected()) {
            String sql = "INSERT INTO DONORS (donor_id, full_name, blood_group, rh_factor, age, gender, contact_number, last_donation_date) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'))";
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, donorId);
                stmt.setString(2, fullName);
                stmt.setString(3, bloodGroup);
                stmt.setString(4, rhFactor);
                stmt.setInt(5, age);
                stmt.setString(6, gender);
                stmt.setString(7, contact);
                stmt.setString(8, lastDonationDate);
                stmt.executeUpdate();
            } catch (SQLException e) {
                System.err.println("[DonorDao] Oracle insert failed: " + e.getMessage());
            }
        }

        Map<String, Object> donor = new LinkedHashMap<>();
        donor.put("donor_id", donorId);
        donor.put("full_name", fullName);
        donor.put("blood_group", bloodGroup);
        donor.put("rh_factor", rhFactor);
        donor.put("blood_type", bloodGroup + rhFactor);
        donor.put("age", age);
        donor.put("gender", gender);
        donor.put("contact_number", contact);
        donor.put("last_donation_date", lastDonationDate);
        donor.put("eligible", isEligible);
        donor.put("eligibility_status", isEligible ? "ELIGIBLE TO DONATE" : "WAITING PERIOD (90 Days interval required)");
        IN_MEMORY_DONORS.add(donor);

        return donor;
    }

    public List<Map<String, Object>> searchAvailableBlood(String bloodGroup, String rhFactor, String pincode, Double maxDistanceKm) {
        HospitalDao hDao = new HospitalDao();
        List<Map<String, Object>> nearbyHospitals = hDao.getNearbyHospitals(null, null, pincode, maxDistanceKm != null ? maxDistanceKm : 30.0);

        InventoryDao invDao = new InventoryDao();
        Map<String, Object> globalInv = invDao.getInventoryDashboard(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allBags = (List<Map<String, Object>>) globalInv.get("inventory_items");

        List<Map<String, Object>> matchingResults = new ArrayList<>();

        for (Map<String, Object> hosp : nearbyHospitals) {
            String hId = (String) hosp.get("hospital_id");
            int matchingUnitsCount = 0;
            List<Map<String, Object>> bagDetails = new ArrayList<>();

            if (allBags != null) {
                for (Map<String, Object> bag : allBags) {
                    if (hId.equalsIgnoreCase((String) bag.get("hospital_id")) &&
                        "AVAILABLE".equalsIgnoreCase((String) bag.get("status"))) {
                        boolean bgMatch = (bloodGroup == null || bloodGroup.isEmpty() || bloodGroup.equalsIgnoreCase((String) bag.get("blood_group")));
                        boolean rhMatch = (rhFactor == null || rhFactor.isEmpty() || rhFactor.equalsIgnoreCase((String) bag.get("rh_factor")));
                        if (bgMatch && rhMatch) {
                            matchingUnitsCount++;
                            bagDetails.add(bag);
                        }
                    }
                }
            }

            if (matchingUnitsCount > 0) {
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("hospital_id", hId);
                match.put("hospital_name", hosp.get("name"));
                match.put("address", hosp.get("address"));
                match.put("phone", hosp.get("phone"));
                match.put("distance_km", hosp.get("distance_km"));
                match.put("latitude", hosp.get("latitude"));
                match.put("longitude", hosp.get("longitude"));
                match.put("matching_units_available", matchingUnitsCount);
                match.put("bags", bagDetails);
                matchingResults.add(match);
            }
        }

        matchingResults.sort(Comparator.comparingDouble(o -> (Double) o.get("distance_km")));
        return matchingResults;
    }
}

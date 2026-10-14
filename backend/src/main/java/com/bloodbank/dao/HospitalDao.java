package com.bloodbank.dao;

import com.bloodbank.config.DatabaseConnection;
import com.bloodbank.util.GeoUtils;

import java.sql.*;
import java.util.*;

/**
 * Hospital Data Access Object.
 * Handles hospital directory, coordinates, and nearby-hospital geolocation searches.
 */
public class HospitalDao {

    // Default Seed Hospitals matching Project Database
    private static final List<Map<String, Object>> SEED_HOSPITALS = new ArrayList<>();

    static {
        Map<String, Object> h1 = new LinkedHashMap<>();
        h1.put("hospital_id", "HOSP-01");
        h1.put("name", "City Central Blood Bank & Hospital");
        h1.put("latitude", 12.971598);
        h1.put("longitude", 77.594562);
        h1.put("address", "102 MG Road, Central District, Bangalore");
        h1.put("pincode", "560001");
        h1.put("phone", "+918023456789");
        h1.put("available_units", 48);
        SEED_HOSPITALS.add(h1);

        Map<String, Object> h2 = new LinkedHashMap<>();
        h2.put("hospital_id", "HOSP-02");
        h2.put("name", "St. Jude Care Multispecialty");
        h2.put("latitude", 12.934440);
        h2.put("longitude", 77.619230);
        h2.put("address", "45 80-Feet Road, Koramangala 4th Block, Bangalore");
        h2.put("pincode", "560034");
        h2.put("phone", "+918098765432");
        h2.put("available_units", 32);
        SEED_HOSPITALS.add(h2);

        Map<String, Object> h3 = new LinkedHashMap<>();
        h3.put("hospital_id", "HOSP-03");
        h3.put("name", "Apex Trauma & Emergency Care");
        h3.put("latitude", 12.916576);
        h3.put("longitude", 77.599908);
        h3.put("address", "78 Bannerghatta Main Road, BTM 2nd Stage, Bangalore");
        h3.put("pincode", "560029");
        h3.put("phone", "+918012344321");
        h3.put("available_units", 25);
        SEED_HOSPITALS.add(h3);

        Map<String, Object> h4 = new LinkedHashMap<>();
        h4.put("hospital_id", "HOSP-04");
        h4.put("name", "Apollo Tech Blood Center");
        h4.put("latitude", 12.969819);
        h4.put("longitude", 77.749977);
        h4.put("address", "ITPB Main Road, Whitefield, Bangalore");
        h4.put("pincode", "560066");
        h4.put("phone", "+918055667788");
        h4.put("available_units", 40);
        SEED_HOSPITALS.add(h4);

        Map<String, Object> h5 = new LinkedHashMap<>();
        h5.put("hospital_id", "HOSP-05");
        h5.put("name", "CMC Hospital & Research Blood Bank");
        h5.put("latitude", 12.924900);
        h5.put("longitude", 79.135400);
        h5.put("address", "Ida Scudder Road, Vellore Town, Vellore");
        h5.put("pincode", "632001");
        h5.put("phone", "+914162281000");
        h5.put("available_units", 60);
        SEED_HOSPITALS.add(h5);
    }

    public List<Map<String, Object>> getNearbyHospitals(Double userLat, Double userLon, String pincode, Double maxRadiusKm) {
        // 1. Resolve coordinates from Pincode if provided and lat/lon are missing
        if ((userLat == null || userLon == null) && pincode != null && !pincode.isEmpty()) {
            double[] coords = GeoUtils.resolvePincode(pincode);
            if (coords != null) {
                userLat = coords[0];
                userLon = coords[1];
            } else {
                // Default fallback to Bangalore Central MG Road coordinates
                userLat = 12.971598;
                userLon = 77.594562;
            }
        } else if (userLat == null || userLon == null) {
            userLat = 12.971598;
            userLon = 77.594562;
        }

        final double finalLat = userLat;
        final double finalLon = userLon;
        final double radius = (maxRadiusKm != null && maxRadiusKm > 0) ? maxRadiusKm : 50.0;

        List<Map<String, Object>> resultList = new ArrayList<>();

        // 2. Fetch from Oracle DB if connected
        if (DatabaseConnection.isConnected()) {
            String sql = "SELECT h.hospital_id, h.name, h.latitude, h.longitude, h.address, h.phone, " +
                         "       COUNT(b.bag_id) AS available_units " +
                         "FROM HOSPITALS h " +
                         "LEFT JOIN BLOOD_BAGS b ON h.hospital_id = b.hospital_id AND b.status = 'AVAILABLE' " +
                         "GROUP BY h.hospital_id, h.name, h.latitude, h.longitude, h.address, h.phone";
            try (Connection conn = DatabaseConnection.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    double hLat = rs.getDouble("latitude");
                    double hLon = rs.getDouble("longitude");
                    double dist = GeoUtils.haversineDistanceKm(finalLat, finalLon, hLat, hLon);

                    if (dist <= radius) {
                        Map<String, Object> h = new LinkedHashMap<>();
                        h.put("hospital_id", rs.getString("hospital_id"));
                        h.put("name", rs.getString("name"));
                        h.put("latitude", hLat);
                        h.put("longitude", hLon);
                        h.put("address", rs.getString("address"));
                        h.put("phone", rs.getString("phone"));
                        h.put("available_units", rs.getInt("available_units"));
                        h.put("distance_km", dist);
                        resultList.add(h);
                    }
                }
            } catch (SQLException e) {
                System.err.println("[HospitalDao] Oracle query failed: " + e.getMessage());
            }
        }

        // 3. Fallback to Seed Hospitals if DB empty or unavailable
        if (resultList.isEmpty()) {
            for (Map<String, Object> seed : SEED_HOSPITALS) {
                double hLat = (Double) seed.get("latitude");
                double hLon = (Double) seed.get("longitude");
                double dist = GeoUtils.haversineDistanceKm(finalLat, finalLon, hLat, hLon);

                if (dist <= radius) {
                    Map<String, Object> copy = new LinkedHashMap<>(seed);
                    copy.put("distance_km", dist);
                    resultList.add(copy);
                }
            }
        }

        // 4. Sort by Nearest Distance first
        resultList.sort(Comparator.comparingDouble(o -> (Double) o.get("distance_km")));
        return resultList;
    }

    public List<Map<String, Object>> getAllHospitals() {
        return new ArrayList<>(SEED_HOSPITALS);
    }
}

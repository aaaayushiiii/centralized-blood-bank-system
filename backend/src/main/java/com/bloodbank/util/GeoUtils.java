package com.bloodbank.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Geolocation utility to calculate Haversine distance between coordinates
 * and resolve PIN codes to (latitude, longitude) pairs.
 */
public class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;

    // Fast-fallback PIN Code Dictionary for Indian cities (Bangalore, Vellore, Chennai, Mumbai, Delhi)
    private static final Map<String, double[]> PINCODE_MAP = new HashMap<>();

    static {
        // Bangalore pincodes
        PINCODE_MAP.put("560001", new double[]{12.971598, 77.594562}); // MG Road / Central
        PINCODE_MAP.put("560034", new double[]{12.934440, 77.619230}); // Koramangala
        PINCODE_MAP.put("560066", new double[]{12.969819, 77.749977}); // Whitefield
        PINCODE_MAP.put("560029", new double[]{12.916576, 77.599908}); // Bannerghatta / BTM
        PINCODE_MAP.put("560011", new double[]{12.925453, 77.593819}); // Jayanagar
        PINCODE_MAP.put("560004", new double[]{12.946300, 77.575000}); // Basavanagudi
        PINCODE_MAP.put("560038", new double[]{12.978400, 77.640800}); // Indiranagar

        // Vellore pincodes (VIT Campus & Medical College)
        PINCODE_MAP.put("632014", new double[]{12.969200, 79.155900}); // VIT Katpadi
        PINCODE_MAP.put("632001", new double[]{12.924900, 79.135400}); // Vellore Town / CMC
        PINCODE_MAP.put("632004", new double[]{12.910000, 79.130000}); // Bagayam / CMC Campus

        // Chennai pincodes
        PINCODE_MAP.put("600001", new double[]{13.082700, 80.270700}); // Chennai Central
        PINCODE_MAP.put("600028", new double[]{13.023800, 80.257000}); // Adyar
        PINCODE_MAP.put("600127", new double[]{12.840600, 80.153400}); // VIT Chennai Campus
    }

    /**
     * Resolves a PIN Code to [latitude, longitude].
     * Returns null if unknown.
     */
    public static double[] resolvePincode(String pincode) {
        if (pincode == null) return null;
        String cleanPin = pincode.trim().replaceAll("[^0-9]", "");
        return PINCODE_MAP.get(cleanPin);
    }

    /**
     * Calculates great-circle distance between two GPS points using the Haversine formula.
     * @return Distance in kilometers rounded to 2 decimal places.
     */
    public static double haversineDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = EARTH_RADIUS_KM * c;
        return Math.round(distance * 100.0) / 100.0;
    }
}

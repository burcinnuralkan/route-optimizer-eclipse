package com.hitit.aviation.core.model;
// iata kodu + koordinat (gcd için)
public record Airport(String code, String name, String city, String country, double lat, double lon) {

    private static final double EARTH_RADIUS_KM = 6371.0;
    // haversine formülü
    public double distanceTo(Airport other) {
        double dLat = Math.toRadians(other.lat - lat); //enlem
        double dLon = Math.toRadians(other.lon - lon); //boylam
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}

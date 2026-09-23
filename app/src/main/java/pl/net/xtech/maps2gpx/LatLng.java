package pl.net.xtech.maps2gpx;

/** A single geographic point, optionally carrying the label it had in the Maps link. */
public final class LatLng {
    public final double lat;
    public final double lon;
    public final String name;
    /** Metres above sea level, or null when unknown - not every point can be resolved. */
    public final Double ele;

    public LatLng(double lat, double lon) {
        this(lat, lon, null, null);
    }

    public LatLng(double lat, double lon, String name) {
        this(lat, lon, name, null);
    }

    public LatLng(double lat, double lon, String name, Double ele) {
        this.lat = lat;
        this.lon = lon;
        this.name = name;
        this.ele = ele;
    }

    public LatLng withName(String newName) {
        return new LatLng(lat, lon, newName, ele);
    }

    public LatLng withEle(Double newEle) {
        return new LatLng(lat, lon, name, newEle);
    }

    /** Plausibility check - filters out garbage picked up from the {@code data=} blob. */
    public boolean isValid() {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                && !(lat == 0 && lon == 0);
    }

    /** Great-circle distance in metres. */
    public static double distanceMeters(LatLng a, LatLng b) {
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(b.lat - a.lat);
        double dLon = Math.toRadians(b.lon - a.lon);
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat
                + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat))
                * sinLon * sinLon;
        return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)
                + (name != null ? " (" + name + ")" : "");
    }
}

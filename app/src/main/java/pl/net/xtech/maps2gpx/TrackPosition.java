package pl.net.xtech.maps2gpx;

import java.util.List;

/** The point on a track nearest to a geographic location, including progress along the track. */
final class TrackPosition {
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    final LatLng point;
    final double distanceMeters;
    final double fraction;
    final double distanceFromTrackMeters;

    private TrackPosition(LatLng point, double distanceMeters, double fraction,
                          double distanceFromTrackMeters) {
        this.point = point;
        this.distanceMeters = distanceMeters;
        this.fraction = fraction;
        this.distanceFromTrackMeters = distanceFromTrackMeters;
    }

    static TrackPosition nearest(List<LatLng> track, LatLng location) {
        if (track == null || track.isEmpty() || location == null) {
            return null;
        }
        if (track.size() == 1) {
            LatLng only = track.get(0);
            return new TrackPosition(only, 0, 0, LatLng.distanceMeters(location, only));
        }

        double bestDistanceSquared = Double.MAX_VALUE;
        double bestDistanceAlong = 0;
        LatLng bestPoint = track.get(0);
        double cumulativeDistance = 0;
        double latitudeScale = Math.cos(Math.toRadians(location.lat));

        for (int i = 1; i < track.size(); i++) {
            LatLng start = track.get(i - 1);
            LatLng end = track.get(i);
            double segmentLength = LatLng.distanceMeters(start, end);

            double startX = longitudeMeters(start.lon - location.lon, latitudeScale);
            double startY = latitudeMeters(start.lat - location.lat);
            double segmentX = longitudeMeters(end.lon - start.lon, latitudeScale);
            double segmentY = latitudeMeters(end.lat - start.lat);
            double segmentSquared = segmentX * segmentX + segmentY * segmentY;
            double projected = segmentSquared == 0 ? 0
                    : -(startX * segmentX + startY * segmentY) / segmentSquared;
            double position = Math.max(0, Math.min(1, projected));
            double nearestX = startX + position * segmentX;
            double nearestY = startY + position * segmentY;
            double distanceSquared = nearestX * nearestX + nearestY * nearestY;

            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestDistanceAlong = cumulativeDistance + position * segmentLength;
                bestPoint = interpolate(start, end, position);
            }
            cumulativeDistance += segmentLength;
        }

        double fraction = cumulativeDistance == 0 ? 0 : bestDistanceAlong / cumulativeDistance;
        return new TrackPosition(bestPoint, bestDistanceAlong, fraction,
                Math.sqrt(bestDistanceSquared));
    }

    static TrackPosition atDistance(List<LatLng> track, double requestedDistanceMeters) {
        if (track == null || track.isEmpty()) {
            return null;
        }
        if (track.size() == 1) {
            return new TrackPosition(track.get(0), 0, 0, 0);
        }

        double totalDistance = 0;
        for (int i = 1; i < track.size(); i++) {
            totalDistance += LatLng.distanceMeters(track.get(i - 1), track.get(i));
        }
        double distance = Math.max(0, Math.min(totalDistance, requestedDistanceMeters));
        double cumulative = 0;
        for (int i = 1; i < track.size(); i++) {
            LatLng start = track.get(i - 1);
            LatLng end = track.get(i);
            double segmentLength = LatLng.distanceMeters(start, end);
            if (cumulative + segmentLength >= distance || i == track.size() - 1) {
                double position = segmentLength == 0 ? 0
                        : (distance - cumulative) / segmentLength;
                return new TrackPosition(interpolate(start, end, position), distance,
                        totalDistance == 0 ? 0 : distance / totalDistance, 0);
            }
            cumulative += segmentLength;
        }
        return new TrackPosition(track.get(track.size() - 1), totalDistance, 1, 0);
    }

    private static LatLng interpolate(LatLng start, LatLng end, double position) {
        Double elevation = null;
        if (start.ele != null && end.ele != null) {
            elevation = start.ele + position * (end.ele - start.ele);
        } else if (start.ele != null) {
            elevation = start.ele;
        } else if (end.ele != null) {
            elevation = end.ele;
        }
        return new LatLng(start.lat + position * (end.lat - start.lat),
                start.lon + position * (end.lon - start.lon), null, elevation);
    }

    private static double longitudeMeters(double longitudeDegrees, double latitudeScale) {
        return Math.toRadians(longitudeDegrees) * EARTH_RADIUS_METERS * latitudeScale;
    }

    private static double latitudeMeters(double latitudeDegrees) {
        return Math.toRadians(latitudeDegrees) * EARTH_RADIUS_METERS;
    }
}
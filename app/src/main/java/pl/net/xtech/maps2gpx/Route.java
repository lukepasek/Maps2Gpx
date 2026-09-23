package pl.net.xtech.maps2gpx;

import java.util.List;

/** A routed track, whichever engine produced it. */
final class Route {
    final List<LatLng> track;
    final double distanceMeters;
    final double durationSeconds;
    /** Human-readable description of what routed this, e.g. "bike" or "Valhalla bicycle/Cross". */
    final String profile;
    /**
     * Short token for the file name - "gravel", "bike", "cross". Kept separate from
     * {@link #profile} so the display label can stay readable while the file name stays
     * terse. Null when the router has no meaningful profile.
     */
    final String profileTag;

    Route(List<LatLng> track, double distanceMeters, double durationSeconds, String profile,
          String profileTag) {
        this.track = track;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.profile = profile;
        this.profileTag = profileTag;
    }

    boolean hasElevation() {
        for (LatLng point : track) {
            if (point.ele != null) {
                return true;
            }
        }
        return false;
    }

    /** Lowest resolved point, or null when the track has no elevation at all. */
    Double minEle() {
        return extremeEle(false);
    }

    Double maxEle() {
        return extremeEle(true);
    }

    private Double extremeEle(boolean highest) {
        Double best = null;
        for (LatLng point : track) {
            if (point.ele == null) {
                continue;
            }
            if (best == null || (highest ? point.ele > best : point.ele < best)) {
                best = point.ele;
            }
        }
        return best;
    }

    /** Total metres climbed. Gaps in the elevation data are skipped, not treated as zero. */
    double ascentMeters() {
        return climb(true);
    }

    double descentMeters() {
        return climb(false);
    }

    private double climb(boolean up) {
        double total = 0;
        Double previous = null;
        for (LatLng point : track) {
            if (point.ele == null) {
                continue;
            }
            if (previous != null) {
                double delta = point.ele - previous;
                if (up == (delta > 0)) {
                    total += Math.abs(delta);
                }
            }
            previous = point.ele;
        }
        return total;
    }

    /** Implied average speed, the quickest way to spot a car route wearing a bike label. */
    double impliedKmh() {
        if (durationSeconds <= 0) {
            return 0;
        }
        return (distanceMeters / 1000.0) / (durationSeconds / 3600.0);
    }
}

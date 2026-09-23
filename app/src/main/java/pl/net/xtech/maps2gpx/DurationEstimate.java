package pl.net.xtech.maps2gpx;

/**
 * Travel time worked out from distance, climb and a nominal speed.
 *
 * <p>Needed because not every router reports a duration - BRouter, for instance, returns a
 * track with no timing at all - and because a router's own figure ignores how the rider
 * actually rides. Climb is charged separately: 500 m of ascent costs a cyclist roughly an
 * hour regardless of how far it is spread.
 */
final class DurationEstimate {

    final double seconds;
    /** Flat-ground speed used, km/h - shown so the number is not a black box. */
    final double kmh;
    /** Vertical metres per hour charged for ascent, or 0 when climb was ignored. */
    final double climbMetersPerHour;

    private DurationEstimate(double seconds, double kmh, double climbMetersPerHour) {
        this.seconds = seconds;
        this.kmh = kmh;
        this.climbMetersPerHour = climbMetersPerHour;
    }

    /** Explicit speed and climb rate, used when a BRouter profile dictates them. */
    static DurationEstimate at(double meters, double ascentMeters, double kmh,
                               double climbMetersPerHour) {
        double hours = (meters / 1000.0) / kmh;
        if (climbMetersPerHour > 0 && ascentMeters > 0) {
            hours += ascentMeters / climbMetersPerHour;
        }
        return new DurationEstimate(hours * 3600.0, kmh, climbMetersPerHour);
    }

    static DurationEstimate of(double meters, double ascentMeters, String travelMode,
                               Settings.Surface surface) {
        double kmh;
        double climbRate;
        if ("walking".equals(travelMode)) {
            kmh = 4.5;
            // Naismith's rule: one extra hour per 600 m of ascent.
            climbRate = 600;
        } else if ("cycling".equals(travelMode)) {
            kmh = cyclingKmh(surface);
            climbRate = 500;
        } else {
            kmh = 50;
            // A car barely notices the gradients we are talking about.
            climbRate = 0;
        }

        double hours = (meters / 1000.0) / kmh;
        if (climbRate > 0 && ascentMeters > 0) {
            hours += ascentMeters / climbRate;
        }
        return new DurationEstimate(hours * 3600.0, kmh, climbRate);
    }

    /** Rougher surfaces are slower, which is what the surface preset already encodes. */
    private static double cyclingKmh(Settings.Surface surface) {
        if (surface == null) {
            return 15;
        }
        switch (surface) {
            case PAVED:
                return 20;
            case MOSTLY_PAVED:
                return 18;
            case TRACKS:
                return 12;
            case GRAVEL:
            default:
                return 15;
        }
    }
}

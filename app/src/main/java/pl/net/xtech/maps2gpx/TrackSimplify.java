package pl.net.xtech.maps2gpx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reduces a recorded track to the handful of points that actually define its shape, so a
 * router can be asked for the same journey rather than handed the answer back.
 *
 * <p>Feeding every recorded point to a router would just reproduce the original line - the
 * whole point of re-routing is to keep the intent (go this way, via roughly here) and let the
 * chosen engine and profile decide the roads.
 */
final class TrackSimplify {

    /**
     * Where Douglas-Peucker starts. A few hundred metres keeps every real turn while dropping
     * GPS jitter and the dozens of points a router emits along one straight road.
     */
    private static final double START_TOLERANCE_METERS = 250;
    /**
     * Where the coarsening starts for points the file stated itself: effectively "keep them
     * all", so only the cap can remove any.
     */
    private static final double FINEST_TOLERANCE_METERS = 1;
    /** Refuse to loop forever on a pathological track. */
    private static final int MAX_ROUNDS = 24;
    /** Bisections used to undo the overshoot from doubling. Eight lands within ~0.4%. */
    private static final int REFINE_ROUNDS = 8;

    /** How far a file's own waypoint may sit from the track and still count as being on it. */
    static final double SNAP_METERS = 250;

    private TrackSimplify() {
    }

    /**
     * The significant points of {@code track}, always including both endpoints, and never more
     * than {@code maxPoints} of them. The result is a whole simplification at one tolerance
     * rather than a truncation of a finer one, so what comes back is a coarser version of the
     * same shape and not the first half of a detailed one.
     *
     * <p>The cap is a limit, not a target: a track whose shape is held by six points comes back
     * as six, however much headroom is left.
     */
    static List<LatLng> significant(List<LatLng> track, int maxPoints) {
        return fit(track, maxPoints, START_TOLERANCE_METERS);
    }

    /**
     * Thins a list of points the file itself stated - {@code <rtept>}s, or its own waypoints -
     * down to {@code maxPoints}, and no further.
     *
     * <p>Deliberately not {@link #significant}: there is no intended level of detail to impose
     * here, because the file already said which points matter. Running these through the
     * 250-metre tolerance instead threw away three quarters of a real 46-point route, which is
     * not thinning but rewriting.
     */
    static List<LatLng> thinTo(List<LatLng> stated, int maxPoints) {
        return fit(stated, maxPoints, FINEST_TOLERANCE_METERS);
    }

    /**
     * Douglas-Peucker at {@code startTolerance}, coarsened only as far as {@code maxPoints}
     * forces. The result is always a whole simplification at one tolerance rather than a
     * truncation of a finer one, so what comes back is a coarser version of the same shape and
     * not the first half of a detailed one.
     */
    private static List<LatLng> fit(List<LatLng> track, int maxPoints, double startTolerance) {
        if (track.size() <= 2 || maxPoints >= track.size()) {
            return new ArrayList<>(track);
        }
        int cap = Math.max(2, maxPoints);

        // Coarsen until the cap is met, remembering the finest tolerance that was still too
        // detailed. The two then bracket the answer.
        double tooDetailed = 0;
        double tolerance = startTolerance;
        List<LatLng> fits = null;
        for (int round = 0; round < MAX_ROUNDS && fits == null; round++) {
            List<LatLng> simplified = douglasPeucker(track, tolerance);
            if (simplified.size() <= cap) {
                fits = simplified;
            } else {
                tooDetailed = tolerance;
                tolerance *= 2;
            }
        }
        if (fits == null) {
            // Still too detailed after 24 doublings, which no real track manages. Keep the
            // endpoints and an even spread, which is at least honest about being a fallback.
            return evenlySpaced(track, cap);
        }
        if (tooDetailed <= 0) {
            // The starting tolerance fitted on the first try; there is nothing to undo, and
            // refining further would pad the result out to the cap for no reason.
            return fits;
        }

        // Doubling overshoots hard: a zigzag that needs 26 points at one tolerance can collapse
        // to 2 at twice that, losing every turn. Bisect back for the most detailed fit.
        double tooCoarse = tolerance;
        for (int round = 0; round < REFINE_ROUNDS && tooCoarse - tooDetailed > 1; round++) {
            double middle = (tooDetailed + tooCoarse) / 2;
            List<LatLng> simplified = douglasPeucker(track, middle);
            if (simplified.size() <= cap) {
                fits = simplified;
                tooCoarse = middle;
            } else {
                tooDetailed = middle;
            }
        }
        return fits;
    }

    /**
     * The file's own waypoints, dropped where they are nowhere near the track and otherwise put
     * into the order the track visits them. GPX does not promise {@code <wpt>} order matches
     * route order, and a file's waypoints may be unrelated points of interest.
     *
     * @return the usable waypoints in track order, which may be fewer than were passed in
     */
    static List<LatLng> orderAlongTrack(List<LatLng> waypoints, List<LatLng> track) {
        List<Anchored> anchored = new ArrayList<>();
        for (LatLng waypoint : waypoints) {
            int nearest = -1;
            double nearestMeters = Double.MAX_VALUE;
            for (int i = 0; i < track.size(); i++) {
                double meters = LatLng.distanceMeters(waypoint, track.get(i));
                if (meters < nearestMeters) {
                    nearestMeters = meters;
                    nearest = i;
                }
            }
            if (nearest >= 0 && nearestMeters <= SNAP_METERS) {
                anchored.add(new Anchored(waypoint, nearest));
            }
        }
        Collections.sort(anchored, new Comparator<Anchored>() {
            @Override
            public int compare(Anchored a, Anchored b) {
                return Integer.compare(a.index, b.index);
            }
        });
        List<LatLng> ordered = new ArrayList<>(anchored.size());
        for (Anchored entry : anchored) {
            ordered.add(entry.point);
        }
        return ordered;
    }

    private static final class Anchored {
        final LatLng point;
        final int index;

        Anchored(LatLng point, int index) {
            this.point = point;
            this.index = index;
        }
    }

    /**
     * Douglas-Peucker, iterative rather than recursive: a 50 000-point track would otherwise
     * risk a stack overflow on the worst-case split.
     */
    static List<LatLng> douglasPeucker(List<LatLng> points, double toleranceMeters) {
        int size = points.size();
        if (size <= 2) {
            return new ArrayList<>(points);
        }
        // Metres-per-degree at this latitude, so the tolerance is a real distance and the
        // shape is not stretched east-west.
        double meanLat = 0;
        for (LatLng point : points) {
            meanLat += point.lat;
        }
        double metersPerLat = 110_574;
        double metersPerLon = 111_320 * Math.cos(Math.toRadians(meanLat / size));

        boolean[] keep = new boolean[size];
        keep[0] = true;
        keep[size - 1] = true;

        int[] stack = new int[size * 2];
        int top = 0;
        stack[top++] = 0;
        stack[top++] = size - 1;

        while (top > 0) {
            int last = stack[--top];
            int first = stack[--top];
            if (last - first < 2) {
                continue;
            }
            double worst = -1;
            int worstIndex = -1;
            for (int i = first + 1; i < last; i++) {
                double distance = perpendicularMeters(points.get(i), points.get(first),
                        points.get(last), metersPerLat, metersPerLon);
                if (distance > worst) {
                    worst = distance;
                    worstIndex = i;
                }
            }
            if (worst <= toleranceMeters || worstIndex < 0) {
                continue;
            }
            keep[worstIndex] = true;
            stack[top++] = first;
            stack[top++] = worstIndex;
            stack[top++] = worstIndex;
            stack[top++] = last;
        }

        List<LatLng> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (keep[i]) {
                out.add(points.get(i));
            }
        }
        return out;
    }

    /** Distance from {@code point} to the segment {@code a}-{@code b}, in metres. */
    private static double perpendicularMeters(LatLng point, LatLng a, LatLng b,
                                              double metersPerLat, double metersPerLon) {
        double px = (point.lon - a.lon) * metersPerLon;
        double py = (point.lat - a.lat) * metersPerLat;
        double bx = (b.lon - a.lon) * metersPerLon;
        double by = (b.lat - a.lat) * metersPerLat;

        double lengthSquared = bx * bx + by * by;
        if (lengthSquared <= 0) {
            // Degenerate segment - a closed loop starts and ends in the same place.
            return Math.hypot(px, py);
        }
        // Clamped projection, so a point beyond either end measures to that end rather than to
        // the infinite line, which would understate a doubling-back detour.
        double t = Math.max(0, Math.min(1, (px * bx + py * by) / lengthSquared));
        return Math.hypot(px - t * bx, py - t * by);
    }

    /** Endpoints plus an even spread between them. */
    private static List<LatLng> evenlySpaced(List<LatLng> points, int count) {
        List<LatLng> out = new ArrayList<>(count);
        int last = points.size() - 1;
        for (int i = 0; i < count; i++) {
            out.add(points.get((int) Math.round((double) i * last / (count - 1))));
        }
        return out;
    }
}

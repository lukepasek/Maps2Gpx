package pl.net.xtech.maps2gpx;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Reads a GPX file back in, so an existing track can be re-routed with this app's own
 * settings instead of whatever produced it.
 *
 * <p>A real pull parser rather than the regex used for {@link BRouterRouter}'s own output:
 * these files come from Strava, Garmin, Komoot and route planners, which differ on attribute
 * order, namespaces, self-closing tags and where they put the name.
 *
 * <p>Three things are pulled out, because three things are needed downstream: the geometry
 * (for the shape comparison and as the source of significant points), any explicit via
 * points, and enough metadata to guess the travel mode.
 */
final class GpxReader {

    /**
     * Beyond this the file is almost certainly not a route but a log, and neither the outline
     * view nor Douglas-Peucker gains anything from the rest. Reading stops here rather than
     * failing, so an oversized file still converts.
     */
    private static final int MAX_POINTS = 50_000;

    /** What a GPX file turned out to contain. */
    static final class Parsed {
        /**
         * The original geometry, as a {@link Route} so ascent, elevation range and length are
         * the same code that measures the new one. Distance is summed from the points; the
         * duration is the recorded elapsed time when the file carried timestamps, else 0.
         */
        final Route original;
        /**
         * Explicit via points: {@code <rtept>} where the file is a route, else {@code <wpt>}.
         * Empty when the file is a bare recorded track.
         */
        final List<LatLng> waypoints;
        /** True when {@link #waypoints} came from {@code <rte>}, i.e. they are the route itself. */
        final boolean waypointsAreRoute;
        /** {@code <metadata><name>} or {@code <trk><name>}, whichever the file had. Nullable. */
        final String name;
        /** {@code <trk><type>} and friends - the only clue to the travel mode. Nullable. */
        final String typeHint;
        /** True when the point cap cut the track short. */
        final boolean truncated;
         /** Maps2Gpx surface ranges embedded in the document, when valid and complete. */
         final SurfaceProfile surfaceProfile;
           /** Original geometry embedded by Maps2Gpx when this file is a rerouted track. */
           final Route sourceRoute;

        Parsed(Route original, List<LatLng> waypoints, boolean waypointsAreRoute, String name,
               String typeHint, boolean truncated, SurfaceProfile surfaceProfile,
               Route sourceRoute) {
            this.original = original;
            this.waypoints = waypoints;
            this.waypointsAreRoute = waypointsAreRoute;
            this.name = name;
            this.typeHint = typeHint;
            this.truncated = truncated;
            this.surfaceProfile = surfaceProfile;
            this.sourceRoute = sourceRoute;
        }
    }

    private GpxReader() {
    }

    /** Closes {@code in}. */
    static Parsed read(InputStream in) throws IOException {
        List<LatLng> trackPoints = new ArrayList<>();
        List<LatLng> sourceTrackPoints = new ArrayList<>();
        List<LatLng> currentTrackPoints = null;
        List<LatLng> routePoints = new ArrayList<>();
        List<LatLng> markers = new ArrayList<>();
        String metadataName = null;
        String trackName = null;
        String typeHint = null;
        String extensionHint = null;
        Long firstTime = null;
        Long lastTime = null;
        boolean truncated = false;
        List<SurfaceProfile.Interval> surfaceIntervals = new ArrayList<>();
        SurfaceProfile embeddedSurfaceProfile = null;
        boolean inSurfaceProfile = false;
        boolean invalidSurfaceProfile = false;

        // Which kind of point element we are inside, so a nested <name> or <ele> lands on the
        // right thing. Points do not nest, so one slot is enough.
        String pointKind = null;
        double lat = 0;
        double lon = 0;
        Double ele = null;
        String pointName = null;
        Long pointTime = null;

        boolean inMetadata = false;
        boolean inTrk = false;
        boolean currentTrackIsSource = false;

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            // Null encoding: honour the XML declaration rather than assuming UTF-8.
            parser.setInput(in, null);

            for (int event = parser.getEventType();
                 event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = local(parser.getName());
                    if ("trkpt".equals(tag) || "rtept".equals(tag) || "wpt".equals(tag)) {
                        Double parsedLat = number(parser.getAttributeValue(null, "lat"));
                        Double parsedLon = number(parser.getAttributeValue(null, "lon"));
                        if (parsedLat == null || parsedLon == null) {
                            continue;
                        }
                        pointKind = tag;
                        lat = parsedLat;
                        lon = parsedLon;
                        ele = null;
                        pointName = null;
                        pointTime = null;
                    } else if ("metadata".equals(tag)) {
                        inMetadata = true;
                    } else if ("trk".equals(tag)) {
                        inTrk = true;
                        currentTrackPoints = new ArrayList<>();
                        currentTrackIsSource = false;
                    } else if ("ele".equals(tag)) {
                        if (pointKind != null) {
                            ele = number(text(parser));
                        }
                    } else if ("time".equals(tag)) {
                        if (pointKind != null) {
                            pointTime = epoch(text(parser));
                        }
                    } else if ("name".equals(tag)) {
                        String value = text(parser);
                        if (pointKind != null) {
                            pointName = value;
                        } else if (inMetadata && metadataName == null) {
                            metadataName = value;
                        } else if (inTrk && trackName == null) {
                            trackName = value;
                        }
                    } else if ("type".equals(tag)) {
                        if (pointKind == null && typeHint == null) {
                            typeHint = text(parser);
                        }
                    } else if (("profile".equals(tag) || "router".equals(tag))
                            && pointKind == null && extensionHint == null) {
                        // Our own m2g: extensions, when the file we are re-routing came from
                        // an earlier run of this app.
                        extensionHint = text(parser);
                    } else if ("role".equals(tag) && inTrk && pointKind == null) {
                        currentTrackIsSource = "source".equals(text(parser).trim());
                    } else if ("surfaceProfile".equals(tag)
                            && embeddedSurfaceProfile == null
                            && "1".equals(parser.getAttributeValue(null, "version"))) {
                        inSurfaceProfile = true;
                        invalidSurfaceProfile = false;
                        surfaceIntervals.clear();
                    } else if ("section".equals(tag) && inSurfaceProfile) {
                        Double from = number(parser.getAttributeValue(null, "from"));
                        Double to = number(parser.getAttributeValue(null, "to"));
                        String surface = parser.getAttributeValue(null, "surface");
                        if (from == null || to == null || surface == null
                                || surface.trim().isEmpty() || from < 0 || to > 1
                                || to <= from) {
                            invalidSurfaceProfile = true;
                        } else {
                            surfaceIntervals.add(new SurfaceProfile.Interval(
                                    from, to, surface.trim()));
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = local(parser.getName());
                    if (tag.equals(pointKind)) {
                        LatLng point = new LatLng(lat, lon, blankToNull(pointName), ele);
                        if (point.isValid()) {
                            if ("trkpt".equals(pointKind)) {
                                int previousPoints = currentTrackIsSource
                                        ? sourceTrackPoints.size() : trackPoints.size();
                                if (currentTrackPoints == null
                                        || previousPoints + currentTrackPoints.size()
                                        >= MAX_POINTS) {
                                    truncated = true;
                                } else {
                                    currentTrackPoints.add(point);
                                    if (pointTime != null) {
                                        if (firstTime == null) {
                                            firstTime = pointTime;
                                        }
                                        lastTime = pointTime;
                                    }
                                }
                            } else if ("rtept".equals(pointKind)) {
                                routePoints.add(point);
                            } else {
                                markers.add(point);
                            }
                        }
                        pointKind = null;
                    } else if ("metadata".equals(tag)) {
                        inMetadata = false;
                    } else if ("trk".equals(tag)) {
                        if (currentTrackPoints != null) {
                            (currentTrackIsSource ? sourceTrackPoints : trackPoints)
                                    .addAll(currentTrackPoints);
                        }
                        currentTrackPoints = null;
                        currentTrackIsSource = false;
                        inTrk = false;
                    } else if ("surfaceProfile".equals(tag) && inSurfaceProfile) {
                        if (!invalidSurfaceProfile && completeSurfaceProfile(surfaceIntervals)) {
                            embeddedSurfaceProfile = new SurfaceProfile(surfaceIntervals);
                        }
                        inSurfaceProfile = false;
                    }
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException("That does not parse as GPX: " + e.getMessage(), e);
        } finally {
            closeQuietly(in);
        }

        // A <rte> is a route in its own right, so it doubles as both geometry and via points.
        List<LatLng> geometry = !trackPoints.isEmpty() ? trackPoints : routePoints;
        if (geometry.size() < 2) {
            throw new IOException("The GPX file has no track or route to re-route ("
                    + geometry.size() + " point(s) found).");
        }

        double elapsed = 0;
        if (firstTime != null && lastTime != null) {
            elapsed = plausibleElapsedSeconds(firstTime, lastTime);
        }
        Route original = new Route(geometry, lengthOf(geometry), elapsed, "source GPX", null);
        Route sourceRoute = sourceTrackPoints.size() < 2 ? null
            : new Route(sourceTrackPoints, lengthOf(sourceTrackPoints), 0,
                "source GPX", null);

        boolean fromRoute = trackPoints.isEmpty() && !routePoints.isEmpty();
        List<LatLng> waypoints = fromRoute ? routePoints
                : (!routePoints.isEmpty() ? routePoints : markers);
        return new Parsed(original, waypoints, fromRoute,
                firstNonBlank(metadataName, trackName),
            firstNonBlank(typeHint, extensionHint), truncated, embeddedSurfaceProfile,
            sourceRoute);
    }

    private static boolean completeSurfaceProfile(List<SurfaceProfile.Interval> intervals) {
        if (intervals.isEmpty()) {
            return false;
        }
        double expectedStart = 0;
        for (SurfaceProfile.Interval interval : intervals) {
            if (Math.abs(interval.startFraction - expectedStart) > 1e-6) {
                return false;
            }
            expectedStart = interval.endFraction;
        }
        return Math.abs(expectedStart - 1) <= 1e-6;
    }

    /**
     * Recognises the mode words the common exporters use, including the profile names this app
     * writes itself ("bike", "BRouter gravel", "Valhalla bicycle/Cross").
     */
    static String modeFromType(String type) {
        if (type == null) {
            return null;
        }
        String t = type.toLowerCase(Locale.US);
        if (contains(t, "bik", "bicycl", "cycl", "ride", "gravel", "mtb", "trekking")) {
            return "cycling";
        }
        if (contains(t, "walk", "hik", "foot", "run", "pedestrian", "trail")) {
            return "walking";
        }
        if (contains(t, "car", "driv", "auto", "motor")) {
            return "driving";
        }
        return null;
    }

    /**
     * A recorded track carries its own answer: 4 km/h is a walk, 20 km/h a ride, 60 km/h a
     * drive. Elapsed time includes stops, so the thresholds are deliberately low.
     *
     * @return null when the file had no usable timestamps
     */
    static String modeFromSpeed(Route original) {
        double kmh = original.impliedKmh();
        if (kmh <= 0) {
            return null;
        }
        if (kmh < 7) {
            return "walking";
        }
        return kmh < 30 ? "cycling" : "driving";
    }

    /** Ignores nonsense: a negative gap, or a "ride" spanning more than a week. */
    private static double plausibleElapsedSeconds(long from, long to) {
        double seconds = (to - from) / 1000.0;
        return seconds > 0 && seconds < 7 * 24 * 3600 ? seconds : 0;
    }

    private static double lengthOf(List<LatLng> track) {
        double total = 0;
        for (int i = 1; i < track.size(); i++) {
            total += LatLng.distanceMeters(track.get(i - 1), track.get(i));
        }
        return total;
    }

    /**
     * The element name without its namespace prefix. GPX files almost always use the default
     * namespace, but a prefixed one is legal and would otherwise match nothing.
     */
    private static String local(String name) {
        if (name == null) {
            return "";
        }
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /**
     * Text content of the element the parser is sitting on. Leaves the parser on the matching
     * END_TAG, which the caller's loop then steps past.
     */
    private static String text(XmlPullParser parser) throws IOException, XmlPullParserException {
        String value = parser.nextText();
        return value == null ? null : value.trim();
    }

    private static Double number(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Seconds-resolution ISO 8601, which is what GPX mandates. Only the elapsed time between
     * two stamps in the same file is ever wanted, so parsing the first 19 characters as UTC is
     * enough - any zone offset cancels out, and fractional seconds do not matter over a ride.
     */
    private static Long epoch(String raw) {
        if (raw == null || raw.length() < 19) {
            return null;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return fmt.parse(raw.substring(0, 19)).getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    private static boolean contains(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        String value = blankToNull(first);
        return value != null ? value : blankToNull(second);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing useful to do with a failure to close a stream we have finished reading.
        }
    }
}

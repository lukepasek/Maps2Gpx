package pl.net.xtech.maps2gpx;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The whole conversion, start to finish:
 * shared text -> URL -> resolved stops -> geocoding -> OSRM geometry -> GPX.
 *
 * <p>{@link #reroute} takes the other entry point: an existing GPX file, reduced back to its
 * significant points and routed again with this app's own engine and profile.
 *
 * <p>Call either off the main thread.
 */
final class Maps2Gpx {

    /** Provenance recorded in the GPX {@code <desc>}. */
    private static final String ORIGIN_LINK = "Converted from a Google Maps link";
    private static final String ORIGIN_GPX = "Re-routed from an imported GPX file";

    /**
     * Via points to hand a router when re-routing a track. Enough to hold the shape of a long
     * ride, few enough that the router still picks the roads - feeding back every recorded
     * point would just reproduce the original line.
     */
    private static final int MAX_REROUTE_STOPS = 25;

    /**
     * How many {@code <wpt>}s a file needs before they are treated as the via chain rather than
     * as decoration. Two is not enough: a track's only waypoints are very often just its start
     * and finish - that is exactly what this app writes - and routing between those two alone
     * would throw away the shape the other 749 points spent the file describing. Three or more
     * is someone deliberately marking a way through.
     */
    private static final int MIN_STATED_WAYPOINTS = 3;

    /**
     * What went wrong in a way the user can act on, or would want to know about.
     *
     * <p>Separate from the progress log on purpose. The log scrolls, and the splash shows only
     * its last line - so "your offline route quietly came from an online server instead" was
     * being whispered at exactly the moment it needed saying. These survive to the summary.
     */
    static final class Notices {
        final List<String> messages = new ArrayList<>();
        /** rd5 tiles the route needed and BRouter did not have. Empty when that was not why. */
        final List<String> missingSegments = new ArrayList<>();

        void add(String message) {
            messages.add(message);
        }

        boolean isEmpty() {
            return messages.isEmpty();
        }
    }

    static final class Result {
        final String gpx;
        final String fileName;
        final String title;
        final String startLabel;
        final String endLabel;
        final int stopCount;
        final int trackPointCount;
        final Route route;
        /** driving / cycling / walking - drives the speed used for the time estimate. */
        final String travelMode;
        /** Destination, for local sunrise/sunset. Null if nothing resolved. */
        final LatLng endPoint;
        /**
         * The track this one was re-routed from, or null for a Maps link. Kept so the summary
         * can draw the two shapes over each other and compare the totals.
         */
        final Route sourceRoute;
        /**
         * Where {@link #travelMode} came from, when that is worth admitting on screen - a GPX
         * file need not say, and guessing wrong changes the route completely. Null when it was
         * not a guess.
         */
        final String travelModeNote;
        /** Warnings worth showing on the summary. Never null. */
        final Notices notices;

        Result(String gpx, String fileName, String title, String startLabel, String endLabel,
               int stopCount, Route route, String travelMode, String travelModeNote,
               LatLng endPoint, Route sourceRoute, Notices notices) {
            this.gpx = gpx;
            this.fileName = fileName;
            this.title = title;
            this.startLabel = startLabel;
            this.endLabel = endLabel;
            this.stopCount = stopCount;
            this.trackPointCount = route.track.size();
            this.route = route;
            this.travelMode = travelMode;
            this.travelModeNote = travelModeNote;
            this.endPoint = endPoint;
            this.sourceRoute = sourceRoute;
            this.notices = notices;
        }
    }

    private Maps2Gpx() {
    }

    static Result convert(Context context, String sharedText, Settings.RoutingEngine engine,
                          Settings.Surface surface, Settings.BRouterProfile brouterProfile,
                          Progress progress) throws IOException {
        String url = MapsLinkParser.extractUrl(sharedText);
        if (url == null) {
            throw new IOException("No link found in the shared text.");
        }
        progress.step("Link: " + url);

        MapsLinkParser.ParsedLink parsed = MapsLinkParser.resolveAndParse(url, progress);
        progress.step("Travel mode: " + parsed.travelMode);
        if (parsed.note != null) {
            progress.step("Note: " + parsed.note);
        }
        progress.step("Found " + parsed.stops.size() + " stop(s):");
        for (MapsLinkParser.Stop stop : parsed.stops) {
            progress.step("  • " + stop.label());
        }

        geocodeMissing(parsed.stops, progress);
        nameUnlabelledStops(parsed.stops, progress);

        // Show where every stop actually ended up - a name that resolved to the wrong
        // city is invisible otherwise.
        progress.step("Resolved stops:");
        for (MapsLinkParser.Stop stop : parsed.stops) {
            progress.step("  • " + (stop.point == null
                    ? stop.label() + " → UNRESOLVED, skipped"
                    : stop.label() + " → " + String.format(java.util.Locale.US, "%.5f,%.5f",
                            stop.point.lat, stop.point.lon)));
        }

        List<LatLng> located = new ArrayList<>();
        for (MapsLinkParser.Stop stop : parsed.stops) {
            if (stop.point != null) {
                located.add(stop.point);
            }
        }
        if (located.isEmpty()) {
            throw new IOException("Could not determine coordinates for any stop.");
        }

        Notices notices = new Notices();
        Route route;
        if (located.size() < 2) {
            progress.step("Only one stop - writing it as a waypoint, no track.");
            route = OsrmRouter.straightLine(located);
        } else {
            route = routeVia(context, engine, surface, brouterProfile, located,
                    parsed.travelMode, notices, progress);
        }

        route = addElevation(route, progress);
        addElevationToStops(parsed.stops, progress);

        return finish(parsed.title(), parsed.stops, route, parsed.resolvedUrl, ORIGIN_LINK,
                engine, surface, brouterProfile, parsed.travelMode, null, null, notices,
                progress);
    }

    /**
     * The other direction: take a GPX file someone else produced, reduce it to the points that
     * define its shape, and route those again with the engine and profile configured here.
     *
     * <p>The original geometry is kept in the result rather than thrown away, so the summary can
     * show what changed - which is the only way to judge whether the new track is the same
     * journey or a different one.
     *
     * @param gpxBytes   the file's raw bytes, not a String: the XML declaration may name an
     *                   encoding other than UTF-8, and only the parser can honour it
     * @param sourceName the file name, used for the title when the GPX has no name of its own
     * @param travelMode the user's own answer, or null to work it out from the file
     */
    static Result reroute(Context context, byte[] gpxBytes, String sourceName,
                          Settings.RoutingEngine engine, Settings.Surface surface,
                          Settings.BRouterProfile brouterProfile, String travelMode,
                          Progress progress) throws IOException {
        GpxReader.Parsed parsed = GpxReader.read(new ByteArrayInputStream(gpxBytes));
        Route source = parsed.original;
        progress.step(String.format(Locale.US, "Read %s: %d points, %.1f km",
                parsed.name != null ? "\"" + parsed.name + "\""
                        : (sourceName != null ? sourceName : "the GPX file"),
                source.track.size(), source.distanceMeters / 1000.0));
        if (parsed.truncated) {
            progress.step("That is only the first " + source.track.size() + " points - the file "
                    + "carries more than a route needs, and the rest was ignored.");
        }

        ModeChoice mode = travelModeOf(parsed, travelMode, progress);
        List<LatLng> vias = viaPointsFor(parsed, progress);

        List<MapsLinkParser.Stop> stops = new ArrayList<>(vias.size());
        for (LatLng point : vias) {
            stops.add(new MapsLinkParser.Stop(point, point.name));
        }
        // Only the two endpoints, not every via point: each lookup costs a second of
        // Nominatim's rate limit, and only the endpoints appear in the file name.
        nameEndpoints(stops, progress);

        Notices notices = new Notices();
        Route route = routeVia(context, engine, surface, brouterProfile, vias, mode.mode,
                notices, progress);
        route = addElevation(route, progress);
        addElevationToStops(stops, progress);
        reportDifference(source, route, progress);

        String title = parsed.name != null ? parsed.name : withoutExtension(sourceName);
        return finish(title, stops, route, null, ORIGIN_GPX, engine, surface, brouterProfile,
                mode.mode, mode.note, source, notices, progress);
    }

    /** Serialises, names the file and packages the result - shared by both entry points. */
    private static Result finish(String title, List<MapsLinkParser.Stop> stops, Route route,
                                 String sourceUrl, String origin,
                                 Settings.RoutingEngine engine, Settings.Surface surface,
                                 Settings.BRouterProfile brouterProfile, String travelMode,
                                 String travelModeNote, Route sourceRoute, Notices notices,
                                 Progress progress) {
        Date now = new Date();
        String startLabel = endpointLabel(stops, true);
        String endLabel = endpointLabel(stops, false);
        String name = title != null && !title.trim().isEmpty()
                ? title : startLabel + " to " + endLabel;
        // The surface preference only means something for the engines that consume it; OSRM
        // has it baked into the profile, so recording one there would be a lie.
        String surfaceLabel = null;
        if (engine == Settings.RoutingEngine.BROUTER) {
            surfaceLabel = brouterProfile.label;
        } else if (engine == Settings.RoutingEngine.VALHALLA) {
            surfaceLabel = surface.label;
        }
        String gpx = GpxWriter.write(name, stops, route, sourceUrl, now, engine.label,
                surfaceLabel, origin);
        String fileName = GpxWriter.suggestFileName(startLabel, endLabel, route.profileTag, now);
        progress.step("GPX ready: " + fileName + " (" + gpx.length() + " bytes)");

        List<LatLng> located = locatedPoints(stops);
        return new Result(gpx, fileName, name, startLabel, endLabel, located.size(), route,
                travelMode, travelModeNote,
                located.isEmpty() ? null : located.get(located.size() - 1), sourceRoute, notices);
    }

    private static List<LatLng> locatedPoints(List<MapsLinkParser.Stop> stops) {
        List<LatLng> located = new ArrayList<>();
        for (MapsLinkParser.Stop stop : stops) {
            if (stop.point != null) {
                located.add(stop.point);
            }
        }
        return located;
    }

    /**
     * Which points to route through. A file that already states its via points is taken at its
     * word; a bare recorded track is reduced to its own significant points instead.
     */
    private static List<LatLng> viaPointsFor(GpxReader.Parsed parsed, Progress progress) {
        List<LatLng> track = parsed.original.track;

        if (parsed.waypointsAreRoute && parsed.waypoints.size() >= 2) {
            progress.step("The file is a route with " + parsed.waypoints.size()
                    + " points - routing through those.");
            return capped(parsed.waypoints, progress);
        }

        if (parsed.waypoints.size() >= MIN_STATED_WAYPOINTS) {
            List<LatLng> onTrack = TrackSimplify.orderAlongTrack(parsed.waypoints, track);
            if (onTrack.size() >= MIN_STATED_WAYPOINTS) {
                progress.step("Using " + onTrack.size() + " of the file's "
                        + parsed.waypoints.size() + " waypoints, in track order.");
                return capped(withEndpointsOf(onTrack, track), progress);
            }
            progress.step("Only " + onTrack.size() + " of the file's " + parsed.waypoints.size()
                    + " waypoint(s) lie on its track - taking the shape's own significant "
                    + "points instead.");
        }

        List<LatLng> significant = TrackSimplify.significant(track, MAX_REROUTE_STOPS);
        progress.step("Reduced " + track.size() + " track points to " + significant.size()
                + " significant points (start, finish and every real turn).");
        return significant;
    }

    /**
     * The track's own first and last point win over the waypoint list: a file's waypoints often
     * mark places along the way without marking where it began.
     */
    private static List<LatLng> withEndpointsOf(List<LatLng> vias, List<LatLng> track) {
        List<LatLng> out = new ArrayList<>(vias);
        LatLng first = track.get(0);
        LatLng last = track.get(track.size() - 1);
        if (LatLng.distanceMeters(first, out.get(0)) > TrackSimplify.SNAP_METERS) {
            out.add(0, first);
        }
        if (LatLng.distanceMeters(last, out.get(out.size() - 1)) > TrackSimplify.SNAP_METERS) {
            out.add(last);
        }
        return out;
    }

    /**
     * Thins an over-long list of stated via points by shape rather than by truncating it, and
     * only as far as the cap forces - these are points the file itself called out.
     */
    private static List<LatLng> capped(List<LatLng> vias, Progress progress) {
        if (vias.size() <= MAX_REROUTE_STOPS) {
            return vias;
        }
        List<LatLng> thinned = TrackSimplify.thinTo(vias, MAX_REROUTE_STOPS);
        progress.step("That is more than a router will take - kept the " + thinned.size()
                + " that define the shape.");
        return thinned;
    }

    /** A travel mode and, when it was not certain, how it was arrived at. */
    private static final class ModeChoice {
        final String mode;
        final String note;

        ModeChoice(String mode, String note) {
            this.mode = mode;
            this.note = note;
        }
    }

    /**
     * How the route was travelled. GPX has no field for it, so this reads the type the exporter
     * wrote, then the speed the timestamps imply - and when neither exists it says out loud that
     * it is guessing, because getting this wrong changes the route rather than just labelling
     * it: a hiking loop routed as a bike ride came back twice as long.
     */
    private static ModeChoice travelModeOf(GpxReader.Parsed parsed, String chosen,
                                           Progress progress) {
        if (chosen != null) {
            progress.step("Travel mode: " + chosen + " (your choice)");
            return new ModeChoice(chosen, null);
        }
        String fromType = GpxReader.modeFromType(parsed.typeHint);
        if (fromType != null) {
            progress.step("Travel mode: " + fromType + " (from the file's \""
                    + parsed.typeHint.trim() + "\")");
            return new ModeChoice(fromType, null);
        }
        String fromSpeed = GpxReader.modeFromSpeed(parsed.original);
        if (fromSpeed != null) {
            progress.step(String.format(Locale.US,
                    "Travel mode: %s (the file's own timestamps imply %.0f km/h)",
                    fromSpeed, parsed.original.impliedKmh()));
            return new ModeChoice(fromSpeed, null);
        }
        progress.step("The file says nothing about how it was travelled and has no timestamps"
                + " - assuming cycling. Reroute to say otherwise; walking and driving take "
                + "quite different roads.");
        return new ModeChoice("cycling", "assumed - the file did not say");
    }

    /** The whole point of re-routing: how far the new track strayed from the old one. */
    private static void reportDifference(Route source, Route route, Progress progress) {
        if (source.distanceMeters <= 0) {
            return;
        }
        double delta = route.distanceMeters - source.distanceMeters;
        progress.step(String.format(Locale.US,
                "Original %.1f km → re-routed %.1f km (%+.1f km, %+.0f%%)",
                source.distanceMeters / 1000.0, route.distanceMeters / 1000.0,
                delta / 1000.0, 100 * delta / source.distanceMeters));
    }

    private static String withoutExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Routes with the chosen engine, then works through the others before giving up and
     * drawing straight lines - a usable track beats no track.
     */
    private static Route routeVia(Context context, Settings.RoutingEngine engine,
                                  Settings.Surface surface,
                                  Settings.BRouterProfile brouterProfile, List<LatLng> stops,
                                  String travelMode, Notices notices, Progress progress) {
        // Worth saying before the attempt rather than reporting it as a routing failure after:
        // the cause is a missing app, not a missing road.
        if (engine == Settings.RoutingEngine.BROUTER && !BRouterRouter.isInstalled(context)) {
            notices.add("Offline routing is selected, but the BRouter app is not installed, "
                    + "so this route came from an online router.");
        }

        List<Settings.RoutingEngine> order = new ArrayList<>();
        order.add(engine);
        for (Settings.RoutingEngine other : Settings.RoutingEngine.values()) {
            if (other == engine) {
                continue;
            }
            // BRouter is only worth falling back to when its app is actually installed.
            if (other == Settings.RoutingEngine.BROUTER && !BRouterRouter.isInstalled(context)) {
                continue;
            }
            order.add(other);
        }

        IOException last = null;
        for (int i = 0; i < order.size(); i++) {
            Settings.RoutingEngine candidate = order.get(i);
            progress.step((i == 0 ? "Routing " + stops.size() + " stops via "
                                  : "Retrying with ")
                    + describe(candidate, travelMode, brouterProfile) + "…");
            try {
                Route route = report(routeWith(context, candidate, surface, brouterProfile, stops,
                        travelMode, progress), progress);
                if (i > 0) {
                    // The engine that produced the track is not the one that was asked for, and
                    // the GPX will carry the chosen engine's label - so say which actually ran.
                    notices.add("Routed with " + describe(candidate, travelMode, brouterProfile)
                            + " instead of " + describe(engine, travelMode, brouterProfile) + ".");
                }
                return route;
            } catch (IOException e) {
                last = e;
                noteFailure(candidate, engine, e, notices);
                progress.step(candidate.name() + " failed: " + e.getMessage());
            }
        }
        progress.step("No router succeeded"
                + (last == null ? "" : " (" + last.getMessage() + ")")
                + " - falling back to straight lines between stops.");
        notices.add("No routing service could be reached, so the track is straight lines "
                + "between the stops - it does not follow roads.");
        return OsrmRouter.straightLine(stops);
    }

    /**
     * Records the failures the user can do something about. A missing rd5 tile is the main one:
     * BRouter cannot fetch it and neither can we, so the only useful response is to name the
     * file and point at the app that downloads it.
     */
    private static void noteFailure(Settings.RoutingEngine candidate,
                                    Settings.RoutingEngine requested, IOException failure,
                                    Notices notices) {
        // Only the engine that was actually asked for. A fallback that also failed is already
        // covered by whichever one worked, or by the straight-line notice when none did - and
        // telling someone who chose OSRM to download rd5 tiles would be pure noise.
        if (candidate != requested) {
            return;
        }
        if (failure instanceof BRouterRouter.MissingSegmentsException) {
            List<String> segments = ((BRouterRouter.MissingSegmentsException) failure).segments;
            for (String segment : segments) {
                if (!notices.missingSegments.contains(segment)) {
                    notices.missingSegments.add(segment);
                }
            }
            notices.add("BRouter has no offline map data for this area. Download "
                    + join(segments) + " in the BRouter app to route it offline.");
            return;
        }
        if (candidate == Settings.RoutingEngine.BROUTER) {
            // Offline was chosen deliberately, so losing it is worth a line whatever the reason.
            notices.add("BRouter could not route this: " + failure.getMessage());
        }
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value);
        }
        return out.toString();
    }

    /**
     * Adds {@code <ele>} to the track. BRouter is elevation-aware and returns heights in its
     * own GPX, so its routes are left alone rather than re-queried.
     */
    private static Route addElevation(Route route, Progress progress) {
        if (route.track.size() < 2) {
            return route;
        }
        if (route.hasElevation()) {
            progress.step("Elevation came from the router itself.");
            return reportClimb(route, progress);
        }
        try {
            progress.step("Fetching elevation for " + route.track.size() + " points…");
            Route enriched = new Route(Elevation.fill(route.track), route.distanceMeters,
                    route.durationSeconds, route.profile, route.profileTag);
            if (!enriched.hasElevation()) {
                progress.step("The elevation service had no data for this area.");
                return route;
            }
            return reportClimb(enriched, progress);
        } catch (IOException e) {
            progress.step("Elevation unavailable (" + e.getMessage()
                    + ") - writing the track without it.");
            return route;
        }
    }

    private static Route reportClimb(Route route, Progress progress) {
        Double low = route.minEle();
        Double high = route.maxEle();
        progress.step(String.format(java.util.Locale.US,
                "Elevation: %.0f–%.0f m, ascent %.0f m, descent %.0f m",
                low == null ? 0 : low, high == null ? 0 : high,
                route.ascentMeters(), route.descentMeters()));
        return route;
    }

    /** Waypoints get heights too, so a GPS unit shows a sane profile at the stops. */
    private static void addElevationToStops(List<MapsLinkParser.Stop> stops, Progress progress) {
        List<MapsLinkParser.Stop> pending = new ArrayList<>();
        List<LatLng> points = new ArrayList<>();
        for (MapsLinkParser.Stop stop : stops) {
            if (stop.point != null && stop.point.ele == null) {
                pending.add(stop);
                points.add(stop.point);
            }
        }
        if (points.isEmpty()) {
            return;
        }
        try {
            List<LatLng> filled = Elevation.fill(points);
            for (int i = 0; i < pending.size(); i++) {
                pending.get(i).point = filled.get(i);
            }
        } catch (IOException e) {
            progress.step("Could not add elevation to the waypoints: " + e.getMessage());
        }
    }

    private static Route routeWith(Context context, Settings.RoutingEngine engine,
                                   Settings.Surface surface,
                                   Settings.BRouterProfile brouterProfile, List<LatLng> stops,
                                   String travelMode, Progress progress) throws IOException {
        switch (engine) {
            case BROUTER:
                return BRouterRouter.route(context, stops, travelMode, brouterProfile, progress);
            case VALHALLA:
                return ValhallaRouter.route(stops, travelMode, surface, progress);
            case OSRM:
            default:
                return OsrmRouter.route(stops, travelMode, progress);
        }
    }

    private static String describe(Settings.RoutingEngine engine, String travelMode,
                                   Settings.BRouterProfile brouterProfile) {
        switch (engine) {
            case BROUTER:
                return "BRouter (" + brouterProfile.label + ", offline)";
            case VALHALLA:
                return "Valhalla (" + ValhallaRouter.costingFor(travelMode) + ")";
            case OSRM:
            default:
                return "OSRM (" + OsrmRouter.profileFor(travelMode) + " profile)";
        }
    }

    private static Route report(Route route, Progress progress) {
        progress.step(String.format(java.util.Locale.US,
                "Route: %.1f km, %d min, %d points [%s]",
                route.distanceMeters / 1000.0,
                Math.round(route.durationSeconds / 60.0),
                route.track.size(), route.profile));
        double kmh = route.impliedKmh();
        if (kmh > 0) {
            progress.step(String.format(java.util.Locale.US,
                    "Implied average speed: %.0f km/h", kmh));
        }
        return route;
    }

    /** Label of the first (or last) stop that actually has coordinates. */
    private static String endpointLabel(List<MapsLinkParser.Stop> stops, boolean first) {
        for (int i = 0; i < stops.size(); i++) {
            MapsLinkParser.Stop stop = stops.get(first ? i : stops.size() - 1 - i);
            if (stop.point == null) {
                continue;
            }
            if (stop.name != null && !stop.name.isEmpty()) {
                return stop.name;
            }
            // Four decimals is ~11 m - plenty to identify a spot, short enough for a name.
            return String.format(java.util.Locale.US, "%.4f,%.4f",
                    stop.point.lat, stop.point.lon);
        }
        return "unknown";
    }

    /**
     * Labels stops that have coordinates but no name - typically "Your location" as the
     * origin, which Google shares as bare numbers. A street or neighbourhood reads far
     * better than 52.291874,21.050762 in the file name and on a GPS unit.
     */
    private static void nameUnlabelledStops(List<MapsLinkParser.Stop> stops,
                                            Progress progress) {
        for (MapsLinkParser.Stop stop : stops) {
            nameStop(stop, progress);
        }
    }

    /**
     * Same lookup, but only for the first and last stop. A re-routed track can have two dozen
     * via points and Nominatim allows one request per second, so naming all of them would add
     * half a minute for labels nothing ever reads - only the endpoints reach the file name.
     */
    private static void nameEndpoints(List<MapsLinkParser.Stop> stops, Progress progress) {
        if (stops.isEmpty()) {
            return;
        }
        nameStop(stops.get(0), progress);
        if (stops.size() > 1) {
            nameStop(stops.get(stops.size() - 1), progress);
        }
    }

    private static void nameStop(MapsLinkParser.Stop stop, Progress progress) {
        if (stop.point == null || (stop.name != null && !stop.name.isEmpty())) {
            return;
        }
        try {
            String label = Nominatim.reverse(stop.point);
            if (label == null) {
                progress.step("No name found near " + stop.point + " - keeping coordinates.");
                return;
            }
            stop.name = label;
            stop.point = stop.point.withName(label);
            progress.step("Named " + String.format(java.util.Locale.US, "%.5f,%.5f",
                    stop.point.lat, stop.point.lon) + " as \"" + label + "\"");
        } catch (IOException e) {
            progress.step("Could not look up a name for " + stop.point
                    + " (" + e.getMessage() + ") - keeping coordinates.");
        }
    }

    private static void geocodeMissing(List<MapsLinkParser.Stop> stops, Progress progress) {
        for (MapsLinkParser.Stop stop : stops) {
            if (stop.point != null || stop.name == null || stop.name.isEmpty()) {
                continue;
            }
            progress.step("Geocoding \"" + stop.name + "\"…");
            try {
                LatLng found = Nominatim.geocode(stop.name);
                if (found == null) {
                    progress.step("  no match - stop skipped");
                } else {
                    stop.point = found;
                    progress.step("  → " + found);
                    warnIfImplausible(stops, stop, progress);
                }
            } catch (IOException e) {
                progress.step("  geocoding failed: " + e.getMessage());
            }
        }
    }

    /** How far a geocoded stop may sit from the nearest known stop before we complain. */
    private static final double GEOCODE_SANITY_METERS = 100_000;

    /**
     * A place name can match the wrong city entirely - "KMD Poland" resolves to both a
     * Warsaw and a Krakow office. We cannot tell which one Google meant, but we can
     * refuse to do it quietly.
     */
    private static void warnIfImplausible(List<MapsLinkParser.Stop> stops,
                                          MapsLinkParser.Stop geocoded, Progress progress) {
        double nearest = Double.MAX_VALUE;
        for (MapsLinkParser.Stop other : stops) {
            if (other == geocoded || other.point == null) {
                continue;
            }
            nearest = Math.min(nearest, LatLng.distanceMeters(geocoded.point, other.point));
        }
        if (nearest != Double.MAX_VALUE && nearest > GEOCODE_SANITY_METERS) {
            progress.step(String.format(java.util.Locale.US,
                    "  WARNING: that is %.0f km from the nearest other stop - the name "
                            + "may have matched the wrong place.", nearest / 1000.0));
        }
    }
}

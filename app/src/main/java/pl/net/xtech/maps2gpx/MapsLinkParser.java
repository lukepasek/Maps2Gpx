package pl.net.xtech.maps2gpx;

import android.net.Uri;
import android.text.Html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever Google Maps put on the clipboard / share sheet into an ordered list
 * of stops.
 *
 * <p>A shared link carries only the stops, never the road geometry - so this class
 * stops at the stops, and {@link OsrmRouter} reconstructs the track.
 */
final class MapsLinkParser {

    /** One stop along the route: either already geocoded, or still just a label. */
    static final class Stop {
        LatLng point;
        String name;

        Stop(LatLng point, String name) {
            this.point = point;
            this.name = name;
        }

        String label() {
            if (name != null && !name.isEmpty()) {
                return name;
            }
            return point != null ? point.toString() : "?";
        }
    }

    static final class ParsedLink {
        String resolvedUrl;
        String travelMode = "driving";
        /** Set when the parse had to guess, so the UI can say so. */
        String note;
        final List<Stop> stops = new ArrayList<>();

        String title() {
            List<String> names = new ArrayList<>();
            for (Stop s : stops) {
                names.add(s.label());
            }
            if (names.isEmpty()) {
                return "Route";
            }
            if (names.size() <= 2) {
                return join(names, " to ");
            }
            return names.get(0) + " to " + names.get(names.size() - 1)
                    + " (" + (names.size() - 2) + " via)";
        }
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private static final String NUM = "(-?\\d+(?:\\.\\d+)?)";

    /**
     * Stop coordinates inside data=. Google uses two different encodings and mixes
     * them within a single URL, so both are scanned in one left-to-right pass to keep
     * the stops in route order:
     *
     * <ul>
     *   <li>{@code !2m2!1d<lon>!2d<lat>} - a stop given as a raw point</li>
     *   <li>{@code !8m2!3d<lat>!4d<lon>} - a stop resolved to a place (has a feature id)</li>
     * </ul>
     *
     * Note the coordinate order is reversed between the two forms.
     */
    private static final Pattern DATA_COORD_STRICT = Pattern.compile(
            "!2m2!1d" + NUM + "!2d" + NUM + "|!8m2!3d" + NUM + "!4d" + NUM);
    /** Same two forms without their length prefixes, for links that omit them. */
    private static final Pattern DATA_COORD_LOOSE = Pattern.compile(
            "!1d" + NUM + "!2d" + NUM + "|!3d" + NUM + "!4d" + NUM);
    /** Place pin encoding: !3d<lat>!4d<lon> */
    private static final Pattern DATA_LAT_LON =
            Pattern.compile("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)");
    /** Travel mode selector: !3e0 driving, !3e1 cycling, !3e2 walking, !3e3 transit. */
    private static final Pattern DATA_MODE = Pattern.compile("!3e(\\d)");

    private static final Pattern BARE_LATLON =
            Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    /** A google maps URL sitting inside an HTML page. */
    private static final Pattern MAPS_URL_IN_HTML = Pattern.compile(
            "https?://(?:www\\.)?(?:maps\\.)?google\\.[a-z.]{2,7}/maps[^\"'\\\\\\s<>)]+");

    private static final String[] SHORT_HOSTS = {
            "maps.app.goo.gl", "goo.gl", "g.co", "maps.google.com/url"
    };

    private MapsLinkParser() {
    }

    /** Pulls the first URL out of shared text such as "Route to X\nhttps://…". */
    static String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL_IN_TEXT.matcher(text);
        if (m.find()) {
            // Trailing punctuation from prose ("… see https://x.y/z.") is not part of the URL.
            return m.group().replaceAll("[.,;:)\\]}>]+$", "");
        }
        return text.trim().isEmpty() ? null : text.trim();
    }

    static ParsedLink resolveAndParse(String rawUrl, Progress progress) throws IOException {
        String url = rawUrl.trim();
        if (isShortLink(url)) {
            progress.step("Expanding short link…");
            String expanded = Http.expand(url);
            if (isShortLink(expanded)) {
                // No Location header - the redirect is inside the page (meta refresh / JS).
                progress.step("Following in-page redirect…");
                String fromBody = findMapsUrlInHtml(Http.get(expanded));
                if (fromBody != null) {
                    expanded = fromBody;
                }
            }
            url = expanded;
        }
        progress.step("Resolved: " + url);

        ParsedLink parsed = parse(url);
        if (parsed.stops.isEmpty()) {
            throw new IOException("No stops found in the link. Is it a Google Maps "
                    + "place or directions link?");
        }
        return parsed;
    }

    static boolean isShortLink(String url) {
        String lower = url.toLowerCase(Locale.US);
        for (String host : SHORT_HOSTS) {
            if (lower.contains("://" + host) || lower.contains("://www." + host)) {
                return true;
            }
        }
        return false;
    }

    private static String findMapsUrlInHtml(String html) {
        Matcher m = MAPS_URL_IN_HTML.matcher(html);
        String best = null;
        while (m.find()) {
            String candidate = unescapeHtml(m.group());
            // Prefer a directions URL over a place/search URL.
            if (candidate.contains("/maps/dir")) {
                return candidate;
            }
            if (best == null) {
                best = candidate;
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private static String unescapeHtml(String s) {
        return Html.fromHtml(s).toString();
    }

    static ParsedLink parse(String url) {
        ParsedLink out = new ParsedLink();
        out.resolvedUrl = url;

        Uri uri = Uri.parse(url);
        String path = uri.getPath() == null ? "" : uri.getPath();

        applyTravelMode(out, uri, url);

        // 1. Documented "Maps URLs" form: /maps/dir/?api=1&origin=…&destination=…
        if (addQueryStops(out, uri)) {
            enrichFromDataBlob(out, url);
            return out;
        }

        // 2. Interactive directions form: /maps/dir/A/B/C/@…/data=…
        if (path.contains("/dir/") || path.endsWith("/dir")) {
            addPathStops(out, uri);
            enrichFromDataBlob(out, url);
            return out;
        }

        // 3. Single place / search / bare coordinate link.
        addSingleLocation(out, uri, url, path);
        return out;
    }

    private static void applyTravelMode(ParsedLink out, Uri uri, String url) {
        String mode = queryParam(uri, "travelmode");
        if (mode == null) {
            mode = queryParam(uri, "dirflg");
        }
        if (mode != null) {
            out.travelMode = normalizeMode(mode);
            return;
        }
        Matcher m = DATA_MODE.matcher(url);
        if (m.find()) {
            switch (m.group(1)) {
                case "1":
                    out.travelMode = "cycling";
                    break;
                case "2":
                    out.travelMode = "walking";
                    break;
                default:
                    out.travelMode = "driving";
                    break;
            }
        }
    }

    private static String normalizeMode(String mode) {
        String m = mode.toLowerCase(Locale.US);
        if (m.startsWith("walk") || m.equals("w") || m.equals("foot")) {
            return "walking";
        }
        if (m.startsWith("bicycl") || m.startsWith("bik") || m.equals("b")) {
            return "cycling";
        }
        return "driving";
    }

    /** @return true if origin/destination query params were present. */
    private static boolean addQueryStops(ParsedLink out, Uri uri) {
        String origin = firstNonEmpty(queryParam(uri, "origin"), queryParam(uri, "saddr"));
        String destination = firstNonEmpty(queryParam(uri, "destination"), queryParam(uri, "daddr"));
        String waypoints = queryParam(uri, "waypoints");

        if (origin == null && destination == null) {
            return false;
        }
        addStopFromText(out, origin);
        if (waypoints != null) {
            for (String wp : waypoints.split("\\|")) {
                addStopFromText(out, wp);
            }
        }
        addStopFromText(out, destination);
        return true;
    }

    private static void addPathStops(ParsedLink out, Uri uri) {
        List<String> segments = uri.getPathSegments();
        int dirIndex = segments.indexOf("dir");
        if (dirIndex < 0) {
            return;
        }
        for (int i = dirIndex + 1; i < segments.size(); i++) {
            String seg = segments.get(i);
            if (seg.isEmpty() || seg.startsWith("@") || seg.startsWith("data=")
                    || seg.startsWith("am=") || seg.equals("preview")) {
                continue;
            }
            addStopFromText(out, seg);
        }
    }

    private static void addSingleLocation(ParsedLink out, Uri uri, String url, String path) {
        // /maps/place/<name>/@lat,lon,17z/data=…!3d<lat>!4d<lon>
        List<LatLng> pinned = matchAll(DATA_LAT_LON, url, false);
        String placeName = null;
        List<String> segments = uri.getPathSegments();
        int placeIndex = segments.indexOf("place");
        if (placeIndex >= 0 && placeIndex + 1 < segments.size()) {
            placeName = decodeName(segments.get(placeIndex + 1));
        }
        if (!pinned.isEmpty()) {
            out.stops.add(new Stop(pinned.get(0).withName(placeName), placeName));
            return;
        }

        String q = firstNonEmpty(queryParam(uri, "q"), queryParam(uri, "query"),
                queryParam(uri, "ll"), queryParam(uri, "center"));
        if (q != null) {
            addStopFromText(out, q);
            if (!out.stops.isEmpty()) {
                return;
            }
        }

        // /maps/@lat,lon,zoom - the map viewport. Last resort, but better than nothing.
        for (String seg : segments) {
            if (seg.startsWith("@")) {
                LatLng point = parseCoordPrefix(seg.substring(1));
                if (point != null) {
                    out.stops.add(new Stop(point.withName(placeName), placeName));
                    return;
                }
            }
        }
        if (placeName != null) {
            out.stops.add(new Stop(null, placeName));
        }
    }

    private static void addStopFromText(ParsedLink out, String raw) {
        if (raw == null) {
            return;
        }
        String text = decodeName(raw);
        if (text.isEmpty() || text.startsWith("place_id:")) {
            // A bare place_id cannot be routed without the Places API; keep it as a label
            // so the stop count still lines up with the data= coordinates.
            out.stops.add(new Stop(null, text.isEmpty() ? null : text));
            return;
        }
        LatLng coord = parseBareCoord(text);
        out.stops.add(coord != null ? new Stop(coord, null) : new Stop(null, text));
    }

    /**
     * Fills in coordinates from the {@code data=} blob, which is the only place a
     * shared interactive link keeps exact stop positions.
     */
    private static void enrichFromDataBlob(ParsedLink out, String url) {
        List<LatLng> coords = coordsFromData(DATA_COORD_STRICT, url);
        if (coords.isEmpty()) {
            coords = coordsFromData(DATA_COORD_LOOSE, url);
        }
        if (coords.isEmpty()) {
            return;
        }

        if (out.stops.isEmpty()) {
            for (LatLng c : coords) {
                out.stops.add(new Stop(c, null));
            }
            return;
        }

        // Google only writes a data= entry for stops it had to resolve; stops the user
        // typed as raw coordinates already sit in the path. So when the count lines up
        // with the stops still missing coordinates, that is the correct pairing - and it
        // beats geocoding a name like "KMD Poland" that matches offices in two cities.
        List<Stop> unresolved = new ArrayList<>();
        for (Stop s : out.stops) {
            if (s.point == null) {
                unresolved.add(s);
            }
        }
        if (!unresolved.isEmpty() && coords.size() == unresolved.size()) {
            for (int i = 0; i < coords.size(); i++) {
                Stop stop = unresolved.get(i);
                stop.point = coords.get(i).withName(stop.name);
            }
            return;
        }

        if (coords.size() == out.stops.size()) {
            for (int i = 0; i < coords.size(); i++) {
                Stop stop = out.stops.get(i);
                if (stop.point == null) {
                    stop.point = coords.get(i).withName(stop.name);
                }
            }
            return;
        }

        if (coords.size() > out.stops.size()) {
            // More coordinates than path segments - typically "Your location" as origin,
            // which Google writes as an empty segment. Trust the coordinates and keep
            // whatever labels we recovered.
            List<String> names = new ArrayList<>();
            for (Stop s : out.stops) {
                names.add(s.name);
            }
            out.stops.clear();
            for (LatLng c : coords) {
                out.stops.add(new Stop(c, null));
            }
            if (names.size() == 1) {
                // A lone label is almost always the destination.
                out.stops.get(out.stops.size() - 1).name = names.get(0);
            } else if (names.size() >= 2) {
                out.stops.get(0).name = names.get(0);
                out.stops.get(out.stops.size() - 1).name = names.get(names.size() - 1);
            }
            out.note = "Link had " + coords.size() + " coordinates but "
                    + names.size() + " label(s); used the coordinates.";
            return;
        }

        // Fewer coordinates than stops: the mapping is ambiguous, so leave the stops
        // alone and let geocoding resolve them by name.
        out.note = "Link had " + coords.size() + " coordinate(s) for " + out.stops.size()
                + " stops; resolving the rest by name.";
    }

    /**
     * Collects stop coordinates from data= in document order, accepting either of
     * Google's two encodings. Groups 1-2 are the {@code !1d<lon>!2d<lat>} form,
     * groups 3-4 the {@code !3d<lat>!4d<lon>} form.
     */
    private static List<LatLng> coordsFromData(Pattern pattern, String url) {
        Matcher m = pattern.matcher(url);
        List<LatLng> result = new ArrayList<>();
        while (m.find()) {
            LatLng point = m.group(1) != null
                    ? toLatLng(m.group(2), m.group(1))
                    : toLatLng(m.group(3), m.group(4));
            if (point != null && point.isValid()) {
                result.add(point);
            }
        }
        return result;
    }

    private static LatLng toLatLng(String lat, String lon) {
        try {
            return new LatLng(Double.parseDouble(lat), Double.parseDouble(lon));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<LatLng> matchAll(Pattern pattern, String url, boolean lonFirst) {
        Matcher m = pattern.matcher(url);
        List<LatLng> result = new ArrayList<>();
        while (m.find()) {
            try {
                double a = Double.parseDouble(m.group(1));
                double b = Double.parseDouble(m.group(2));
                LatLng point = lonFirst ? new LatLng(b, a) : new LatLng(a, b);
                if (point.isValid()) {
                    result.add(point);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed pair
            }
        }
        return result;
    }

    private static LatLng parseBareCoord(String text) {
        Matcher m = BARE_LATLON.matcher(text);
        if (!m.matches()) {
            return null;
        }
        try {
            LatLng point = new LatLng(Double.parseDouble(m.group(1)),
                    Double.parseDouble(m.group(2)));
            return point.isValid() ? point : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses "57.05,9.92" out of "57.05,9.92,14z". */
    private static LatLng parseCoordPrefix(String text) {
        String[] parts = text.split(",");
        if (parts.length < 2) {
            return null;
        }
        return parseBareCoord(parts[0] + "," + parts[1]);
    }

    private static String decodeName(String raw) {
        String s = raw.replace('+', ' ');
        try {
            s = Uri.decode(s);
        } catch (RuntimeException ignored) {
            // keep the raw form
        }
        return s.trim();
    }

    private static String queryParam(Uri uri, String key) {
        try {
            String v = uri.getQueryParameter(key);
            return v == null || v.trim().isEmpty() ? null : v.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }
}

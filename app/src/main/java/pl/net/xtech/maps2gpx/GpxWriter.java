package pl.net.xtech.maps2gpx;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Serialises stops + track geometry as GPX 1.1. */
final class GpxWriter {

    private static final String NS = "http://www.topografix.com/GPX/1/1";

    /**
     * Our own namespace for the routing details. GPX 1.1 has nowhere to record a surface
     * preference, and {@code <extensions>} is the sanctioned place for anything the schema
     * does not define - readers that do not know the namespace simply skip it.
     */
    private static final String EXT_NS = "https://pl.net.xtech/maps2gpx/1";
    private static final String EXT_PREFIX = "m2g";

    private GpxWriter() {
    }

    /**
     * @param origin how the stops were arrived at, for {@code <desc>} - a converted Maps link
     *               and a re-routed GPX file are not the same provenance, and the file is the
     *               only place that record survives.
     */
    static String write(String title, List<MapsLinkParser.Stop> stops, Route route,
                        String sourceUrl, Date now, String engineLabel, String surfaceLabel,
                        String origin) {
        StringBuilder sb = new StringBuilder(route.track.size() * 64 + 512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"Maps2Gpx\"\n")
                .append("     xmlns=\"").append(NS).append("\"\n")
                .append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
                .append("     xmlns:").append(EXT_PREFIX).append("=\"").append(EXT_NS)
                .append("\"\n")
                .append("     xsi:schemaLocation=\"").append(NS)
                .append(" http://www.topografix.com/GPX/1/1/gpx.xsd\">\n");

        // Both names carry the profile: readers differ on which one they show in their track
        // list, and OneLap for one takes the metadata name, so putting it only on <trk> left
        // it invisible.
        String displayName = trackName(title, route);

        sb.append("  <metadata>\n");
        sb.append("    <name>").append(esc(displayName)).append("</name>\n");
        sb.append("    <desc>")
                .append(esc(describe(route, origin)))
                .append("</desc>\n");
        if (sourceUrl != null) {
            sb.append("    <link href=\"").append(esc(sourceUrl)).append("\">\n")
                    .append("      <text>Source Google Maps link</text>\n")
                    .append("    </link>\n");
        }
        sb.append("    <time>").append(iso8601(now)).append("</time>\n");
        sb.append("  </metadata>\n");

        // Stops as waypoints, so a GPS unit shows the actual via points.
        int index = 1;
        for (MapsLinkParser.Stop stop : stops) {
            if (stop.point == null) {
                continue;
            }
            String name = stop.name != null && !stop.name.isEmpty()
                    ? stop.name
                    : positionLabel(index, stops.size());
            sb.append("  <wpt lat=\"").append(coord(stop.point.lat))
                    .append("\" lon=\"").append(coord(stop.point.lon)).append("\">\n");
            // GPX 1.1 fixes the child order: <ele> has to precede <name>.
            if (stop.point.ele != null) {
                sb.append("    <ele>").append(elevation(stop.point.ele)).append("</ele>\n");
            }
            sb.append("    <name>").append(esc(name)).append("</name>\n")
                    .append("    <sym>Flag</sym>\n")
                    .append("  </wpt>\n");
            index++;
        }

        sb.append("  <trk>\n");
        sb.append("    <name>").append(esc(displayName)).append("</name>\n");
        sb.append("    <type>").append(esc(route.profile)).append("</type>\n");
        // GPX 1.1 fixes the order inside <trk>: extensions must precede <trkseg>.
        appendRoutingExtensions(sb, route, engineLabel, surfaceLabel);
        sb.append("    <trkseg>\n");
        for (LatLng p : route.track) {
            sb.append("      <trkpt lat=\"").append(coord(p.lat))
                    .append("\" lon=\"").append(coord(p.lon));
            if (p.ele == null) {
                sb.append("\"/>\n");
            } else {
                sb.append("\"><ele>").append(elevation(p.ele)).append("</ele></trkpt>\n");
            }
        }
        sb.append("    </trkseg>\n");
        sb.append("  </trk>\n");
        sb.append("</gpx>\n");
        return sb.toString();
    }

    /**
     * The track name is what most GPX readers show in their list, so the profile belongs
     * there - it is the difference between three otherwise identical routes.
     */
    private static String trackName(String title, Route route) {
        if (route.profileTag == null || route.profileTag.trim().isEmpty()) {
            return title;
        }
        return title + " (" + route.profileTag.trim() + ")";
    }

    /**
     * Routing details that GPX itself cannot express: which engine, which profile and which
     * surface preference produced this track.
     */
    private static void appendRoutingExtensions(StringBuilder sb, Route route,
                                                String engineLabel, String surfaceLabel) {
        boolean hasProfile = route.profileTag != null && !route.profileTag.trim().isEmpty();
        boolean hasEngine = engineLabel != null && !engineLabel.trim().isEmpty();
        boolean hasSurface = surfaceLabel != null && !surfaceLabel.trim().isEmpty();
        if (!hasProfile && !hasEngine && !hasSurface) {
            return;
        }
        sb.append("    <extensions>\n");
        if (hasEngine) {
            appendExt(sb, "engine", engineLabel);
        }
        if (hasProfile) {
            appendExt(sb, "profile", route.profileTag.trim());
        }
        if (hasSurface) {
            appendExt(sb, "surface", surfaceLabel);
        }
        appendExt(sb, "router", route.profile);
        sb.append("    </extensions>\n");
    }

    private static void appendExt(StringBuilder sb, String element, String value) {
        sb.append("      <").append(EXT_PREFIX).append(':').append(element).append('>')
                .append(esc(value))
                .append("</").append(EXT_PREFIX).append(':').append(element).append(">\n");
    }

    private static String positionLabel(int index, int total) {
        if (index == 1) {
            return "Start";
        }
        if (index == total) {
            return "Finish";
        }
        return "Via " + (index - 1);
    }

    private static String describe(Route route, String origin) {
        String from = origin == null || origin.trim().isEmpty()
                ? "Converted" : origin.trim();
        if (route.distanceMeters <= 0) {
            return from + " (" + route.profile + ")";
        }
        String text = String.format(Locale.US,
                "%s - %s, %.1f km, approx. %d min",
                from, route.profile, route.distanceMeters / 1000.0,
                Math.round(route.durationSeconds / 60.0));
        if (route.hasElevation()) {
            text += String.format(Locale.US, ", ascent %.0f m, descent %.0f m",
                    route.ascentMeters(), route.descentMeters());
        }
        return text;
    }

    /** Two decimals is well past DEM accuracy, but keeps a valid xsd:decimal. */
    private static String elevation(double meters) {
        return String.format(Locale.US, "%.2f", meters);
    }

    /**
     * {@code <start>_to_<end>[_<profile>]_<timestamp>.gpx} - the endpoints are the whole
     * point, and the profile distinguishes the same route ridden different ways, which is
     * exactly what rerouting produces.
     */
    static String suggestFileName(String startLabel, String endLabel, String profileTag,
                                  Date now) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now);
        String profile = profileTag == null || profileTag.trim().isEmpty()
                ? "" : "_" + slug(profileTag);
        return slug(startLabel) + "_to_" + slug(endLabel) + profile + "_" + stamp + ".gpx";
    }

    private static String slug(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "unknown";
        }
        // Letters that no amount of Unicode decomposition will split into base + accent.
        String out = label.trim()
                .replace("ł", "l").replace("Ł", "L")
                .replace("ø", "o").replace("Ø", "O")
                .replace("æ", "ae").replace("Æ", "AE")
                .replace("đ", "d").replace("Đ", "D")
                .replace("ß", "ss");

        // Decompose the rest and drop the accents, so "Nieporęt" becomes "Nieporet"
        // rather than losing the letter entirely and reading "Nieport".
        out = Normalizer.normalize(out, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");

        // No dots anywhere but the extension - apps that infer the type from the last dot
        // mis-detect names like "52.2918-21.0507_to_X.gpx". Commas become dashes and the
        // decimal points underscores, so coordinates stay readable.
        out = out.replace(',', '-')
                .replace('.', '_')
                .replaceAll("\\s+", "_")
                .replaceAll("[^A-Za-z0-9_-]", "");
        if (out.isEmpty()) {
            return "unknown";
        }
        return out.length() > 40 ? out.substring(0, 40) : out;
    }

    private static String coord(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static String iso8601(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(date);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&apos;");
                    break;
                default:
                    // Strip control characters that are illegal in XML 1.0.
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }
}

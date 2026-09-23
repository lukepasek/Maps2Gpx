package pl.net.xtech.maps2gpx;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rebuilds the road-following geometry between the stops using the public OSRM
 * demo server. The shared Google link has no geometry in it, so this is what turns
 * a handful of stops back into a track.
 */
final class OsrmRouter {

    /**
     * FOSSGIS runs one OSRM instance per profile. This matters: the better-known
     * router.project-osrm.org hosts <em>only</em> the car profile and silently ignores the
     * profile in the URL, so a cycling link there comes back as a driving route (verified:
     * driving, bike and foot all returned an identical 24.5 km / 32 min route, where the
     * FOSSGIS bike instance returned 20.1 km / 83 min for the same pair).
     */
    private static final String FOSSGIS_BASE = "https://routing.openstreetmap.de/";
    /** Car-only, used only if the profile-specific instance is unreachable. */
    private static final String FALLBACK_BASE = "https://router.project-osrm.org/route/v1/driving/";

    private static final int PRECISION = 6;
    /** The public servers reject very long coordinate lists. */
    private static final int MAX_STOPS = 100;

    private OsrmRouter() {
    }

    /** Maps a Google travel mode onto an OSRM profile name. */
    static String profileFor(String travelMode) {
        if ("walking".equals(travelMode)) {
            return "foot";
        }
        if ("cycling".equals(travelMode)) {
            return "bike";
        }
        return "car";
    }

    /** The FOSSGIS instance that actually implements the profile. */
    private static String baseFor(String profile) {
        return FOSSGIS_BASE + "routed-" + profile + "/route/v1/driving/";
    }

    static Route route(List<LatLng> stops, String travelMode, Progress progress)
            throws IOException {
        if (stops.size() < 2) {
            throw new IOException("Need at least two stops to build a route.");
        }
        if (stops.size() > MAX_STOPS) {
            throw new IOException("Too many stops for the public OSRM server ("
                    + stops.size() + " > " + MAX_STOPS + ").");
        }

        String profile = profileFor(travelMode);
        try {
            return request(baseFor(profile), stops, profile);
        } catch (IOException e) {
            progress.step("The '" + profile + "' router failed (" + e.getMessage() + ")");
            if ("car".equals(profile)) {
                progress.step("Retrying the car-only fallback server…");
                return request(FALLBACK_BASE, stops, "car");
            }
            // Say so loudly: a car route is not what a cycling or walking link asked for.
            progress.step("WARNING: falling back to a car-only router, so the track will be "
                    + "a DRIVING route, not " + profile + ".");
            return request(FALLBACK_BASE, stops, "car-fallback");
        }
    }

    private static Route request(String base, List<LatLng> stops, String profile)
            throws IOException {
        StringBuilder coords = new StringBuilder();
        for (LatLng stop : stops) {
            if (coords.length() > 0) {
                coords.append(';');
            }
            coords.append(String.format(Locale.US, "%.6f,%.6f", stop.lon, stop.lat));
        }

        String url = base + coords
                + "?overview=full&geometries=polyline" + PRECISION
                + "&steps=false&alternatives=false&continue_straight=false";

        String body = Http.get(url, Http.APP_USER_AGENT);
        try {
            JSONObject json = new JSONObject(body);
            String code = json.optString("code", "");
            if (!"Ok".equals(code)) {
                throw new IOException("OSRM: " + code + " - "
                        + json.optString("message", "no route found"));
            }
            JSONArray routes = json.getJSONArray("routes");
            if (routes.length() == 0) {
                throw new IOException("OSRM returned no routes.");
            }
            JSONObject route = routes.getJSONObject(0);
            List<LatLng> track =
                    PolylineCodec.decode(route.getString("geometry"), PRECISION);
            if (track.isEmpty()) {
                throw new IOException("OSRM returned an empty geometry.");
            }
            return new Route(track, route.optDouble("distance", 0),
                    route.optDouble("duration", 0), profile, profile);
        } catch (JSONException e) {
            throw new IOException("Unexpected OSRM response", e);
        }
    }

    /** Straight-line fallback so a GPX is still produced when routing is unavailable. */
    static Route straightLine(List<LatLng> stops) {
        return new Route(new ArrayList<>(stops), 0, 0, "straight-line", "straight-line");
    }
}

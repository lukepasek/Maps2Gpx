package pl.net.xtech.maps2gpx;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Routing via the public FOSSGIS Valhalla instance.
 *
 * <p>Chosen over OSRM when the surface matters: OSRM bakes its preferences into the Lua
 * profile at graph-build time and exposes no way to change them over HTTP, whereas
 * Valhalla takes {@code bicycle_type} and {@code avoid_bad_surfaces} per request.
 */
final class ValhallaRouter {

    private static final String ENDPOINT = "https://valhalla1.openstreetmap.de/route";
    /** Valhalla encodes its shape with six decimals, same as we ask OSRM for. */
    private static final int PRECISION = 6;
    private static final int MAX_STOPS = 50;

    private ValhallaRouter() {
    }

    /** Valhalla costing model for a Google travel mode. */
    static String costingFor(String travelMode) {
        if ("walking".equals(travelMode)) {
            return "pedestrian";
        }
        if ("cycling".equals(travelMode)) {
            return "bicycle";
        }
        return "auto";
    }

    static Route route(List<LatLng> stops, String travelMode, Settings.Surface surface,
                       Progress progress) throws IOException {
        if (stops.size() < 2) {
            throw new IOException("Need at least two stops to build a route.");
        }
        if (stops.size() > MAX_STOPS) {
            throw new IOException("Too many stops for the public Valhalla server ("
                    + stops.size() + " > " + MAX_STOPS + ").");
        }

        String costing = costingFor(travelMode);
        boolean cycling = "bicycle".equals(costing);
        String label = cycling
                ? "Valhalla bicycle/" + surface.bicycleType
                : "Valhalla " + costing;
        if (cycling) {
            progress.step("Surface preference: " + surface.label
                    + " (bicycle_type=" + surface.bicycleType
                    + ", avoid_bad_surfaces=" + surface.avoidBadSurfaces + ")");
        } else {
            progress.step("Surface preference does not apply to " + costing + " routing.");
        }

        // For a bike the bicycle_type is the distinguishing choice; otherwise the costing is.
        String tag = cycling ? surface.bicycleType.toLowerCase(java.util.Locale.US) : costing;

        String body = Http.postJson(ENDPOINT, buildRequest(stops, costing, surface),
                Http.APP_USER_AGENT);
        return parse(body, label, tag);
    }

    private static String buildRequest(List<LatLng> stops, String costing,
                                       Settings.Surface surface) throws IOException {
        try {
            JSONArray locations = new JSONArray();
            for (LatLng stop : stops) {
                JSONObject point = new JSONObject();
                point.put("lat", stop.lat);
                point.put("lon", stop.lon);
                locations.put(point);
            }

            JSONObject request = new JSONObject();
            request.put("locations", locations);
            request.put("costing", costing);
            request.put("units", "kilometers");

            if ("bicycle".equals(costing)) {
                JSONObject bicycle = new JSONObject();
                bicycle.put("bicycle_type", surface.bicycleType);
                bicycle.put("avoid_bad_surfaces", surface.avoidBadSurfaces);
                JSONObject costingOptions = new JSONObject();
                costingOptions.put("bicycle", bicycle);
                request.put("costing_options", costingOptions);
            }
            return request.toString();
        } catch (JSONException e) {
            throw new IOException("Could not build the Valhalla request", e);
        }
    }

    private static Route parse(String body, String label, String tag) throws IOException {
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("error")) {
                throw new IOException("Valhalla: " + json.optString("error"));
            }
            JSONObject trip = json.getJSONObject("trip");
            JSONObject summary = trip.getJSONObject("summary");
            JSONArray legs = trip.getJSONArray("legs");

            List<LatLng> track = new ArrayList<>();
            for (int i = 0; i < legs.length(); i++) {
                String shape = legs.getJSONObject(i).optString("shape", "");
                if (shape.isEmpty()) {
                    continue;
                }
                List<LatLng> leg = PolylineCodec.decode(shape, PRECISION);
                // Consecutive legs share their boundary point; keep only one copy.
                int from = (!track.isEmpty() && !leg.isEmpty()
                        && samePoint(track.get(track.size() - 1), leg.get(0))) ? 1 : 0;
                for (int p = from; p < leg.size(); p++) {
                    track.add(leg.get(p));
                }
            }
            if (track.isEmpty()) {
                throw new IOException("Valhalla returned an empty geometry.");
            }
            // "units":"kilometers" means summary.length is in km; time is in seconds.
            return new Route(track, summary.optDouble("length", 0) * 1000.0,
                    summary.optDouble("time", 0), label, tag);
        } catch (JSONException e) {
            throw new IOException("Unexpected Valhalla response", e);
        }
    }

    private static boolean samePoint(LatLng a, LatLng b) {
        return Math.abs(a.lat - b.lat) < 1e-6 && Math.abs(a.lon - b.lon) < 1e-6;
    }
}

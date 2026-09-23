package pl.net.xtech.maps2gpx;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fills in {@code <ele>} for track points, using Valhalla's elevation ("skadi") endpoint on
 * the same free FOSSGIS host used for routing - no API key.
 *
 * <p>BRouter is elevation-aware and returns heights in its own GPX, so routes from it never
 * come through here.
 */
final class Elevation {

    private static final String ENDPOINT = "https://valhalla1.openstreetmap.de/height";

    /**
     * Points per request. The endpoint accepted 6000 in testing, so this is a politeness
     * limit and a cap on request size rather than a hard constraint.
     */
    private static final int CHUNK = 2000;

    private Elevation() {
    }

    /**
     * @return a copy of {@code points} with elevation set wherever the service had data;
     *         points it could not resolve are returned unchanged.
     */
    static List<LatLng> fill(List<LatLng> points) throws IOException {
        List<LatLng> out = new ArrayList<>(points.size());
        for (int start = 0; start < points.size(); start += CHUNK) {
            List<LatLng> chunk = points.subList(start, Math.min(points.size(), start + CHUNK));
            double[] heights = query(chunk);
            for (int i = 0; i < chunk.size(); i++) {
                LatLng point = chunk.get(i);
                out.add(Double.isNaN(heights[i]) ? point : point.withEle(heights[i]));
            }
        }
        return out;
    }

    private static double[] query(List<LatLng> points) throws IOException {
        String body;
        try {
            JSONArray shape = new JSONArray();
            for (LatLng point : points) {
                JSONObject entry = new JSONObject();
                entry.put("lat", point.lat);
                entry.put("lon", point.lon);
                shape.put(entry);
            }
            JSONObject request = new JSONObject();
            request.put("shape", shape);
            request.put("range", false);
            body = request.toString();
        } catch (JSONException e) {
            throw new IOException("Could not build the elevation request", e);
        }

        String response = Http.postJson(ENDPOINT, body, Http.APP_USER_AGENT);
        try {
            JSONObject json = new JSONObject(response);
            if (json.has("error")) {
                throw new IOException("Elevation service: " + json.optString("error"));
            }
            JSONArray heights = json.getJSONArray("height");
            if (heights.length() != points.size()) {
                throw new IOException("Elevation service returned " + heights.length()
                        + " heights for " + points.size() + " points.");
            }
            double[] result = new double[points.size()];
            for (int i = 0; i < result.length; i++) {
                // Points with no DEM coverage come back as JSON null.
                result[i] = heights.isNull(i) ? Double.NaN : heights.optDouble(i, Double.NaN);
            }
            return result;
        } catch (JSONException e) {
            throw new IOException("Unexpected elevation response", e);
        }
    }
}

package pl.net.xtech.maps2gpx;

import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/**
 * OpenStreetMap's Nominatim service, used two ways:
 * forward - a stop the link only named, with no coordinates in the data= blob;
 * reverse - a stop that has coordinates but no name, so it can be labelled with a
 * nearby street or place instead of raw numbers.
 */
final class Nominatim {

    private static final String SEARCH = "https://nominatim.openstreetmap.org/search";
    private static final String REVERSE = "https://nominatim.openstreetmap.org/reverse";

    /** Nominatim's usage policy allows at most one request per second. */
    private static final long MIN_INTERVAL_MS = 1100;
    private static long lastCallMs;

    /**
     * Address components to label a point with, most specific first. Whichever is present
     * wins, so a street name beats a suburb, which beats a city.
     */
    private static final String[] NAME_KEYS = {
            "road", "pedestrian", "footway", "cycleway", "path",
            "neighbourhood", "suburb", "hamlet", "quarter",
            "village", "town", "city", "municipality", "county"
    };

    private Nominatim() {
    }

    /** Self-throttling so callers cannot accidentally breach the usage policy. */
    private static synchronized void throttle() {
        long now = SystemClock.elapsedRealtime();
        long wait = MIN_INTERVAL_MS - (now - lastCallMs);
        if (lastCallMs != 0 && wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallMs = SystemClock.elapsedRealtime();
    }

    /**
     * Nearest named thing to a point - a street if there is one, else a
     * neighbourhood/village/city. Null when Nominatim has nothing to offer.
     */
    static String reverse(LatLng point) throws IOException {
        String url = Uri.parse(REVERSE)
                .buildUpon()
                .appendQueryParameter("lat", String.format(Locale.US, "%.6f", point.lat))
                .appendQueryParameter("lon", String.format(Locale.US, "%.6f", point.lon))
                .appendQueryParameter("format", "jsonv2")
                .appendQueryParameter("zoom", "17")
                .appendQueryParameter("addressdetails", "1")
                .build()
                .toString();

        throttle();
        String body = Http.get(url, Http.APP_USER_AGENT);
        try {
            JSONObject json = new JSONObject(body);
            JSONObject address = json.optJSONObject("address");
            if (address != null) {
                for (String key : NAME_KEYS) {
                    String value = address.optString(key, "");
                    if (!value.trim().isEmpty()) {
                        return value.trim();
                    }
                }
            }
            String name = json.optString("name", "");
            if (!name.trim().isEmpty()) {
                return name.trim();
            }
            // Last resort: the leading component of the full display name.
            String display = json.optString("display_name", "");
            if (!display.trim().isEmpty()) {
                return display.split(",")[0].trim();
            }
            return null;
        } catch (JSONException e) {
            throw new IOException("Unexpected reverse-geocoder response", e);
        }
    }

    static LatLng geocode(String query) throws IOException {
        String url = Uri.parse(SEARCH)
                .buildUpon()
                .appendQueryParameter("q", query)
                .appendQueryParameter("format", "json")
                .appendQueryParameter("limit", "1")
                .build()
                .toString();

        throttle();
        String body = Http.get(url, Http.APP_USER_AGENT);
        try {
            JSONArray results = new JSONArray(body);
            if (results.length() == 0) {
                return null;
            }
            JSONObject first = results.getJSONObject(0);
            LatLng point = new LatLng(
                    Double.parseDouble(first.getString("lat")),
                    Double.parseDouble(first.getString("lon")),
                    query);
            return point.isValid() ? point : null;
        } catch (JSONException | NumberFormatException e) {
            throw new IOException("Unexpected geocoder response for \"" + query + "\"", e);
        }
    }
}

package pl.net.xtech.maps2gpx;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** Minimal HTTP helper: manual redirect following plus a plain GET. */
final class Http {

    /** A desktop UA gets us the canonical /maps/dir/ URL instead of an app-install page. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int MAX_REDIRECTS = 10;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    /**
     * Honest identifier for OSM services - both Nominatim and the OSRM demo server
     * require a real User-Agent naming the client.
     */
    static final String APP_USER_AGENT = "Maps2Gpx/1.0 (Android; GPX export tool)";

    private Http() {
    }

    /**
     * Follows redirects by hand so we can see every hop, unwrap Google's consent
     * interstitial and stop as soon as we reach a real maps URL.
     */
    static String expand(String startUrl) throws IOException {
        String current = startUrl;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = open(current, "GET");
            conn.setInstanceFollowRedirects(false);
            try {
                int code = conn.getResponseCode();
                if (code < 300 || code >= 400) {
                    return current;
                }
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) {
                    return current;
                }
                current = unwrapConsent(resolve(current, location));
            } finally {
                conn.disconnect();
            }
        }
        return current;
    }

    /** consent.google.com/…?continue=<real url> - dig the real target back out. */
    private static String unwrapConsent(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && host.contains("consent.")) {
                String cont = uri.getQueryParameter("continue");
                if (cont != null && !cont.isEmpty()) {
                    return cont;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through - keep the URL as-is
        }
        return url;
    }

    private static String resolve(String base, String location) {
        try {
            return URI.create(base).resolve(location).toString();
        } catch (RuntimeException e) {
            return location;
        }
    }

    static String get(String url) throws IOException {
        return get(url, USER_AGENT);
    }

    static String get(String url, String userAgent) throws IOException {
        HttpURLConnection conn = open(url, "GET");
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setInstanceFollowRedirects(true);
        return readResponse(conn, url);
    }

    /** JSON POST - Valhalla only parses its request reliably from a POST body. */
    static String postJson(String url, String json, String userAgent) throws IOException {
        HttpURLConnection conn = open(url, "POST");
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setInstanceFollowRedirects(true);
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(json.getBytes("UTF-8"));
        }
        return readResponse(conn, url);
    }

    private static String readResponse(HttpURLConnection conn, String url) throws IOException {
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new IOException("HTTP " + code + " with no body for " + url);
            }
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            String body = readAll(in);
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": " + trim(body));
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY_BYTES) {
                break;
            }
        }
        in.close();
        return out.toString("UTF-8");
    }

    private static String trim(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}

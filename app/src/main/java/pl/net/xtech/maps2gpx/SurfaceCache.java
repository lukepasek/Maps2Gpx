package pl.net.xtech.maps2gpx;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Persistent surface analysis keyed by GPX URI and elevation-independent route geometry. */
final class SurfaceCache {
    private static final String TAG = "Maps2Gpx";
    private static final int VERSION = 1;

    private SurfaceCache() {
    }

    static SurfaceProfile load(Context context, String gpxKey, List<LatLng> track) {
        File file = file(context, gpxKey);
        if (!file.isFile()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(read(file));
            if (root.optInt("version") != VERSION
                    || !geometryKey(track).equals(root.optString("geometry"))) {
                return null;
            }
            JSONArray values = root.getJSONArray("intervals");
            List<SurfaceProfile.Interval> intervals = new ArrayList<>(values.length());
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                intervals.add(new SurfaceProfile.Interval(value.getDouble("from"),
                        value.getDouble("to"), value.getString("surface")));
            }
            return intervals.isEmpty() ? null : new SurfaceProfile(intervals);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not read cached surface data for " + gpxKey, e);
            return null;
        }
    }

    static void save(Context context, String gpxKey, List<LatLng> track,
                     SurfaceProfile profile) {
        File destination = file(context, gpxKey);
        File parent = destination.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "Could not create surface cache directory " + parent);
            return;
        }
        try {
            JSONArray intervals = new JSONArray();
            for (SurfaceProfile.Interval interval : profile.intervals) {
                intervals.put(new JSONObject()
                        .put("from", interval.startFraction)
                        .put("to", interval.endFraction)
                        .put("surface", interval.surface));
            }
            JSONObject root = new JSONObject()
                    .put("version", VERSION)
                    .put("gpx", gpxKey)
                    .put("geometry", geometryKey(track))
                    .put("intervals", intervals);
            File temporary = new File(parent, destination.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if ((!destination.exists() || destination.delete())
                    && !temporary.renameTo(destination)) {
                throw new IOException("Could not replace " + destination);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not cache surface data for " + gpxKey, e);
        }
    }

    static void delete(Context context, String gpxKey) {
        File cached = file(context, gpxKey);
        if (cached.exists() && !cached.delete()) {
            Log.w(TAG, "Could not delete surface cache " + cached);
        }
    }

    private static File file(Context context, String gpxKey) {
        return new File(new File(context.getFilesDir(), "surface-cache"),
                digest(gpxKey) + ".json");
    }

    private static String geometryKey(List<LatLng> track) {
        MessageDigest digest = sha256();
        for (LatLng point : track) {
            updateLong(digest, Double.doubleToLongBits(point.lat));
            updateLong(digest, Double.doubleToLongBits(point.lon));
        }
        return hex(digest.digest());
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static String digest(String value) {
        return hex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
}
package pl.net.xtech.maps2gpx;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import btools.routingapp.IBRouterService;

/**
 * Offline routing through the installed BRouter app.
 *
 * <p>Not a build dependency: BRouter exposes an exported service and we bind to it by
 * explicit component, since it declares no intent-filter. The only thing we compile is a
 * copy of its one-method AIDL. In exchange, routing works with no network at all - provided
 * the user has downloaded BRouter's rd5 segments for the region.
 */
final class BRouterRouter {

    private static final String TAG = "Maps2Gpx";
    static final String PACKAGE = "btools.routingapp";
    private static final String SERVICE = "btools.routingapp.BRouterService";

    /** Binding goes through another process being started, so allow some slack. */
    private static final long BIND_TIMEOUT_MS = 20_000;
    private static final String MAX_RUNNING_TIME_SECONDS = "60";

    private BRouterRouter() {
    }

    /**
     * BRouter had no map data for the area. Typed rather than a plain IOException with a good
     * message, because this is the one routing failure the user can actually fix - and fixing
     * it means being told which files to download, so the segment names have to survive the
     * throw rather than being buried in prose the caller would have to parse back out.
     */
    static final class MissingSegmentsException extends IOException {
        private static final long serialVersionUID = 1L;

        /** The rd5 tiles this route's bounding box needed. */
        final List<String> segments;

        MissingSegmentsException(String message, List<String> segments, Throwable cause) {
            super(message, cause);
            this.segments = new ArrayList<>(segments);
        }
    }

    /**
     * The rd5 segment file covering a point. BRouter splits the planet into 5x5 degree tiles
     * named after the south-west corner, so this is pure arithmetic - no need to read
     * BRouter's storage, which lives in its own app directory anyway.
     *
     * <p>Matches the documented examples: West 48 / North 37 falls in {@code W50_N35.rd5},
     * East 7 / North 47 in {@code E5_N45.rd5}.
     */
    static String segmentName(double lat, double lon) {
        int lonBase = (int) Math.floor(lon / 5.0) * 5;
        int latBase = (int) Math.floor(lat / 5.0) * 5;
        return (lonBase < 0 ? "W" + (-lonBase) : "E" + lonBase)
                + "_" + (latBase < 0 ? "S" + (-latBase) : "N" + latBase) + ".rd5";
    }

    /** Every segment the stops' bounding box touches, in a stable order. */
    static List<String> requiredSegments(List<LatLng> stops) {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (LatLng stop : stops) {
            minLat = Math.min(minLat, stop.lat);
            maxLat = Math.max(maxLat, stop.lat);
            minLon = Math.min(minLon, stop.lon);
            maxLon = Math.max(maxLon, stop.lon);
        }
        List<String> names = new ArrayList<>();
        for (double lat = Math.floor(minLat / 5) * 5; lat <= maxLat; lat += 5) {
            for (double lon = Math.floor(minLon / 5) * 5; lon <= maxLon; lon += 5) {
                String name = segmentName(lat, lon);
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Whether an error from BRouter is about missing map data rather than, say, an unroutable
     * pair of points or a profile that is not installed.
     */
    static boolean looksLikeMissingSegments(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.toLowerCase(Locale.US);
        return lower.contains("rd5") || lower.contains("datafile")
                || lower.contains("data file") || lower.contains("no data")
                || lower.contains("segment")
                || lower.contains("not found and no bounding box");
    }

    static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Play Store page for BRouter, with a browser fallback for devices without Play. */
    static Intent installIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PACKAGE));
    }

    static Intent installIntentFallback() {
        return new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=" + PACKAGE));
    }

    /** BRouter's vehicle keyword for a Google travel mode. */
    static String vehicleFor(String travelMode) {
        if ("walking".equals(travelMode)) {
            return "foot";
        }
        if ("cycling".equals(travelMode)) {
            return "bicycle";
        }
        return "motorcar";
    }

    /*
     * Why this class names a profile instead of sending v/fast:
     *
     * BRouterService resolves v + fast indirectly, building the key <mode>_<fast|short> and
     * looking it up in its own config to find a .brf file - so the mapping lives in the
     * user's BRouter install, not here. Measured on the test device, it made no difference
     * at all: fast=1 and fast=0 both returned an identical 24.2 km / 876-point route for the
     * same stops. The "profile" parameter takes priority over v/fast and names the .brf
     * directly, which is what actually gives control.
     */

    static Route route(Context context, List<LatLng> stops, String travelMode,
                       Settings.BRouterProfile profile, Progress progress) throws IOException {
        if (stops.size() < 2) {
            throw new IOException("Need at least two stops to build a route.");
        }
        if (!isInstalled(context)) {
            throw new IOException("The BRouter app is not installed.");
        }

        progress.step("BRouter profile: " + profile.label + " (" + profile.file + ".brf)");
        // The link's own mode and the chosen profile can disagree; the explicit choice wins,
        // but say so rather than quietly routing a car trip on a gravel bike profile.
        String linkVehicle = vehicleFor(travelMode);
        if (!matches(linkVehicle, profile)) {
            progress.step("Note: the link is a " + travelMode
                    + " route, but the " + profile.label + " profile was chosen.");
        }

        // Which map tiles this route needs. Worth stating up front: if BRouter then fails,
        // the log already says exactly what to download.
        List<String> segments = requiredSegments(stops);
        progress.step("Needs BRouter segments: " + join(segments));

        String gpx;
        try {
            gpx = callService(context, buildParams(stops, profile), progress);
        } catch (IOException e) {
            if (looksLikeMissingSegments(e.getMessage())) {
                throw new MissingSegmentsException("BRouter has no map data for this area - "
                        + "download " + join(segments) + " in the BRouter app. ("
                        + e.getMessage() + ")", segments, e);
            }
            throw e;
        }
        // Getting a track back is itself the proof that the segments were present.
        progress.step("BRouter segments present for this area.");
        return parse(gpx, "BRouter " + profile.file, profile.file);
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

    private static boolean matches(String vehicle, Settings.BRouterProfile profile) {
        if ("motorcar".equals(vehicle)) {
            return profile == Settings.BRouterProfile.CAR;
        }
        if ("foot".equals(vehicle)) {
            return profile == Settings.BRouterProfile.HIKING
                    || profile == Settings.BRouterProfile.SHORTEST;
        }
        return profile != Settings.BRouterProfile.CAR
                && profile != Settings.BRouterProfile.HIKING;
    }

    private static Bundle buildParams(List<LatLng> stops, Settings.BRouterProfile profile) {
        double[] lats = new double[stops.size()];
        double[] lons = new double[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            lats[i] = stops.get(i).lat;
            lons[i] = stops.get(i).lon;
        }
        Bundle params = new Bundle();
        params.putDoubleArray("lats", lats);
        params.putDoubleArray("lons", lons);
        // BRouterService resolves this to <baseDir>/brouter/profiles2/<profile>.brf, hence no
        // suffix here. It takes priority over v/fast, which is the point - those were measured
        // to have no effect.
        params.putString("profile", profile.file);
        params.putString("trackFormat", "gpx");
        params.putString("maxRunningTime", MAX_RUNNING_TIME_SECONDS);
        // pathToFileResult is deliberately left unset: that makes BRouter return the track
        // itself rather than writing a file we would then have to find and read.
        return params;
    }

    /** Binds, calls, unbinds. Runs on the caller's background thread. */
    private static String callService(Context context, Bundle params, Progress progress)
            throws IOException {
        final ArrayBlockingQueue<IBRouterService> connected = new ArrayBlockingQueue<>(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                connected.offer(IBRouterService.Stub.asInterface(binder));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "BRouter service disconnected");
            }
        };

        Intent intent = new Intent();
        intent.setClassName(PACKAGE, SERVICE);

        boolean bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            safeUnbind(context, connection);
            throw new IOException("Could not bind to the BRouter service. Is BRouter installed "
                    + "and allowed to run?");
        }
        try {
            progress.step("Waiting for the BRouter service…");
            IBRouterService service = connected.poll(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (service == null) {
                throw new IOException("BRouter did not connect within "
                        + (BIND_TIMEOUT_MS / 1000) + " s.");
            }
            String result = service.getTrackFromParams(params);
            if (result == null || result.trim().isEmpty()) {
                throw new IOException("BRouter returned nothing. Are the routing segments "
                        + "for this area downloaded in BRouter?");
            }
            // BRouter reports failures by returning the error text in the same String as a
            // successful track, so the only way to tell them apart is to look for a track.
            if (!result.contains("<trkpt")) {
                throw new IOException("BRouter: " + result.trim());
            }
            // The header carries BRouter's own totals when it emits them; worth seeing when
            // distance or duration come back missing.
            Log.d(TAG, "BRouter response head: "
                    + result.substring(0, Math.min(500, result.length())).replace('\n', ' '));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for BRouter.", e);
        } catch (RemoteException e) {
            throw new IOException("The BRouter service call failed: " + e.getMessage(), e);
        } finally {
            safeUnbind(context, connection);
        }
    }

    private static void safeUnbind(Context context, ServiceConnection connection) {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException e) {
            // Never bound in the first place; nothing to release.
            Log.d(TAG, "BRouter service was not bound", e);
        }
    }

    /** Group 1 = attributes, group 3 = element body (null when self-closing). */
    private static final Pattern TRKPT =
            Pattern.compile("<trkpt([^>]*?)(/>|>(.*?)</trkpt>)", Pattern.DOTALL);
    private static final Pattern ATTR_LAT = Pattern.compile("lat=\"(-?\\d+(?:\\.\\d+)?)\"");
    private static final Pattern ATTR_LON = Pattern.compile("lon=\"(-?\\d+(?:\\.\\d+)?)\"");
    /** BRouter is elevation-aware, so its own output already carries heights. */
    private static final Pattern ELE =
            Pattern.compile("<ele>\\s*(-?\\d+(?:\\.\\d+)?)\\s*</ele>");
    /** BRouter puts its totals in a comment, e.g. "track-length = 17632 total-time = 4231". */
    private static final Pattern TRACK_LENGTH = Pattern.compile("track-length\\s*=\\s*(\\d+)");
    private static final Pattern TOTAL_TIME = Pattern.compile("total-time\\s*=\\s*(\\d+)");

    static Route parse(String gpx, String label, String tag) throws IOException {
        List<LatLng> track = new ArrayList<>();
        Matcher points = TRKPT.matcher(gpx);
        while (points.find()) {
            String attrs = points.group(1);
            Matcher lat = ATTR_LAT.matcher(attrs);
            Matcher lon = ATTR_LON.matcher(attrs);
            if (!lat.find() || !lon.find()) {
                continue;
            }
            try {
                LatLng point = new LatLng(Double.parseDouble(lat.group(1)),
                        Double.parseDouble(lon.group(1)), null,
                        elevationIn(points.group(3)));
                if (point.isValid()) {
                    track.add(point);
                }
            } catch (NumberFormatException ignored) {
                // skip a malformed point rather than losing the whole track
            }
        }
        if (track.isEmpty()) {
            throw new IOException("BRouter returned a track with no points.");
        }

        double meters = firstNumber(TRACK_LENGTH, gpx);
        if (meters <= 0) {
            meters = lengthOf(track);
        }
        return new Route(track, meters, firstNumber(TOTAL_TIME, gpx), label, tag);
    }

    private static Double elevationIn(String trkptBody) {
        if (trkptBody == null) {
            return null;
        }
        Matcher m = ELE.matcher(trkptBody);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double firstNumber(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double lengthOf(List<LatLng> track) {
        double total = 0;
        for (int i = 1; i < track.size(); i++) {
            total += LatLng.distanceMeters(track.get(i - 1), track.get(i));
        }
        return total;
    }
}

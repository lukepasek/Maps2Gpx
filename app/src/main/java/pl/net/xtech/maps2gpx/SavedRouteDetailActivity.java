package pl.net.xtech.maps2gpx;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** In-app details and file actions for one GPX entry from the library. */
public class SavedRouteDetailActivity extends Activity implements LocationListener {
    private static final String TAG = "Maps2Gpx";
    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_NAME = "name";
    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private static final double MAX_PROFILE_DISTANCE_FROM_TRACK_METERS = 250;
    private static final String EXTRA_HANDOFF = "handoff";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Uri sourceUri;
    private String displayName;
    private byte[] sourceBytes;
    private Uri shareableUri;
    private GpxReader.Parsed parsed;
    private SurfaceProfile surfaceProfile;
    private AlertDialog sourceUpdateDialog;
    private LocationManager locationManager;
    private boolean locationPermissionRequested;
    private boolean sourceUpdateInProgress;
    private boolean sourceHasSurfaceProfile;
    private boolean surfaceSaveOffered;
    private boolean reversed;
    private boolean deleting;
    private boolean handoffRequested;
    private boolean handoffStarted;

    static Intent intentFor(Context context, OutputFolder.Entry entry) {
        return intentFor(context, entry.uri, entry.displayName);
    }

    static Intent intentFor(Context context, Uri uri, String displayName) {
        return new Intent(context, SavedRouteDetailActivity.class)
            .setDataAndType(uri, GpxFiles.MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_NAME, displayName);
    }

    static Intent intentForHandoff(Context context, Uri uri, String displayName) {
        return intentFor(context, uri, displayName).putExtra(EXTRA_HANDOFF, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
        setContentView(R.layout.activity_saved_detail);
        applySystemBarInsets();
        sourceUri = Uri.parse(getIntent().getStringExtra(EXTRA_URI));
        displayName = getIntent().getStringExtra(EXTRA_NAME);
        handoffRequested = getIntent().getBooleanExtra(EXTRA_HANDOFF, false);
        findViewById(R.id.detail_back).setOnClickListener(v -> finish());
        findViewById(R.id.detail_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.detail_info).setOnClickListener(v -> showGpxInformation());
        findViewById(R.id.detail_center_location).setOnClickListener(v -> centerCurrentLocation());
        findViewById(R.id.detail_open).setOnClickListener(v -> openExternal());
        findViewById(R.id.detail_share).setOnClickListener(v -> share());
        findViewById(R.id.detail_reroute).setOnClickListener(v -> reroute());
        findViewById(R.id.detail_reverse).setOnClickListener(v -> reverse());
        setActionsEnabled(false);
        load();
    }

    private void configureEdgeToEdge() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                window, window.getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.detail_root);
        View topBar = findViewById(R.id.detail_top_bar);
        View profile = findViewById(R.id.detail_profile_panel);
        View progress = findViewById(R.id.detail_progress);
        TrackMapView mapView = findViewById(R.id.detail_track_outline);
        int topLeft = topBar.getPaddingLeft();
        int topRight = topBar.getPaddingRight();
        int profileLeft = profile.getPaddingLeft();
        int profileTop = profile.getPaddingTop();
        int profileRight = profile.getPaddingRight();
        int profileBottom = profile.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            topBar.setPadding(topLeft, bars.top, topRight, 0);
            mapView.setCompassTopMargin(bars.top + dp(68));
            profile.setPadding(profileLeft, profileTop, profileRight,
                profileBottom + bars.bottom);
            FrameLayout.LayoutParams progressLayout =
                    (FrameLayout.LayoutParams) progress.getLayoutParams();
            progressLayout.topMargin = bars.top + dp(56);
            progress.setLayoutParams(progressLayout);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void load() {
        executor.execute(() -> {
            try {
                byte[] bytes = GpxFiles.readAll(this, sourceUri);
                GpxReader.Parsed sourceParsed = GpxReader.read(new ByteArrayInputStream(bytes));
                boolean wasMissingElevation = !sourceParsed.original.hasElevation();
                GpxReader.Parsed loaded = enrichElevation(sourceParsed);
                boolean generatedElevation = wasMissingElevation
                        && loaded.original.hasElevation() && !loaded.truncated;
                Uri cached = cacheHandoff(displayName, bytes);
                SurfaceProfile availableSurface = loaded.surfaceProfile;
                if (availableSurface == null) {
                    availableSurface = SurfaceCache.load(this, sourceUri.toString(),
                            loaded.original.track);
                }
                SurfaceProfile loadedSurface = availableSurface;
                runOnUiThread(() -> {
                    show(bytes, loaded, cached, loadedSurface);
                    handoffIfRequested();
                    if (generatedElevation) {
                        confirmSaveGeneratedElevation();
                    } else {
                        maybeConfirmSaveSurface();
                    }
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not load " + sourceUri, e);
                runOnUiThread(() -> showError(e.getMessage()));
            }
        });
    }

    private GpxReader.Parsed enrichElevation(GpxReader.Parsed loaded) {
        Route route = loaded.original;
        if (route.hasElevation() || route.track.size() < 2) {
            return loaded;
        }
        try {
            Route enriched = new Route(Elevation.fill(route.track), route.distanceMeters,
                    route.durationSeconds, route.profile, route.profileTag);
            if (!enriched.hasElevation()) {
                return loaded;
            }
            return new GpxReader.Parsed(enriched, loaded.waypoints,
                    loaded.waypointsAreRoute, loaded.name, loaded.typeHint, loaded.truncated,
                    loaded.surfaceProfile, loaded.sourceRoute);
        } catch (IOException e) {
            Log.w(TAG, "Could not fetch elevation for " + displayName, e);
            return loaded;
        }
    }

    private void confirmSaveGeneratedElevation() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.save_elevation_title)
                .setMessage(getString(R.string.save_elevation_message, displayName))
                .setPositiveButton(R.string.save, (ignored, which) -> saveGeneratedElevation())
                .setNegativeButton(R.string.not_now, null)
                .create();
        sourceUpdateDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            sourceUpdateDialog = null;
            maybeConfirmSaveSurface();
        });
        dialog.show();
    }

    private void saveGeneratedElevation() {
        if (sourceBytes == null || parsed == null || reversed) {
            return;
        }
        sourceUpdateInProgress = true;
        setActionsEnabled(false);
        findViewById(R.id.detail_progress).setVisibility(View.VISIBLE);
        byte[] originalBytes = sourceBytes;
        Route elevatedRoute = parsed.original;
        executor.execute(() -> {
            try {
                byte[] updated = GpxElevationWriter.write(originalBytes, elevatedRoute.track);
                try (OutputStream output = getContentResolver()
                        .openOutputStream(sourceUri, "wt")) {
                    if (output == null) {
                        throw new IOException("The storage provider refused write access.");
                    }
                    output.write(updated);
                }
                Uri cached = cacheHandoff(displayName, updated);
                runOnUiThread(() -> {
                    sourceBytes = updated;
                    shareableUri = cached;
                    sourceUpdateInProgress = false;
                    findViewById(R.id.detail_progress).setVisibility(View.GONE);
                    setActionsEnabled(true);
                    toast(getString(R.string.elevation_saved));
                    maybeConfirmSaveSurface();
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not save generated elevation to " + sourceUri, e);
                runOnUiThread(() -> {
                    sourceUpdateInProgress = false;
                    findViewById(R.id.detail_progress).setVisibility(View.GONE);
                    setActionsEnabled(true);
                    toast(getString(R.string.elevation_save_failed));
                    maybeConfirmSaveSurface();
                });
            }
        });
    }

    private void show(byte[] bytes, GpxReader.Parsed loaded, Uri cached,
                      SurfaceProfile cachedSurface) {
        sourceHasSurfaceProfile = loaded.surfaceProfile != null;
        surfaceProfile = cachedSurface;
        updateRoute(bytes, loaded, cached);
        startLocationTracking();
        if (cachedSurface == null) {
            analyzeSurfaces(loaded.original.track, reversed);
        }
    }

    private void analyzeSurfaces(java.util.List<LatLng> track,
                                 boolean analyzedReversedState) {
        showSurfaceSummary(null, true);
        executor.execute(() -> {
            try {
                SurfaceProfile analyzed = SurfaceAnalyzer.fetch(track);
                if (!analyzedReversedState) {
                    SurfaceCache.save(this, sourceUri.toString(), track, analyzed);
                }
                runOnUiThread(() -> {
                    surfaceProfile = reversed == analyzedReversedState
                            ? analyzed : analyzed.reversed();
                    applySurfaceProfile();
                        maybeConfirmSaveSurface();
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not analyze surfaces for " + displayName, e);
                runOnUiThread(() -> showSurfaceSummary(null, false));
            }
        });
    }

    private void updateRoute(byte[] bytes, GpxReader.Parsed loaded, Uri cached) {
        sourceBytes = bytes;
        parsed = loaded;
        shareableUri = cached;
        findViewById(R.id.detail_progress).setVisibility(View.GONE);
        String name = loaded.name != null
            ? loaded.name : SavedRoutesActivity.readableName(displayName);
        ((TextView) findViewById(R.id.detail_name)).setText(name);
        ((TextView) findViewById(R.id.detail_profile_name)).setText(name);
        ((TextView) findViewById(R.id.detail_summary)).setText(summaryOf(loaded.original));
        findViewById(R.id.detail_reverse).setBackgroundResource(reversed
                ? R.drawable.floating_action_reversed_background
                : R.drawable.floating_action_background);

        TrackMapView outline = findViewById(R.id.detail_track_outline);
        outline.setReferenceTrack(loaded.sourceRoute == null
            ? null : loaded.sourceRoute.track);
        outline.setTrack(loaded.original.track, sourceUri.toString());
        ElevationProfileView profile = findViewById(R.id.detail_elevation_profile);
        profile.setOverlayMode(true);
        profile.setOnInspectionPositionChangedListener(outline::setInspectionPosition);
        profile.setTrack(loaded.original.track);
        profile.setVisibility(profile.hasProfile() ? View.VISIBLE : View.GONE);
        applySurfaceProfile();
        findViewById(R.id.detail_profile_panel).setVisibility(View.VISIBLE);
        setActionsEnabled(true);
    }

    private void applySurfaceProfile() {
        ((TrackMapView) findViewById(R.id.detail_track_outline))
                .setSurfaceProfile(surfaceProfile);
        ((ElevationProfileView) findViewById(R.id.detail_elevation_profile))
                .setSurfaceProfile(surfaceProfile);
        showSurfaceSummary(surfaceProfile, false);
    }

    private void showSurfaceSummary(SurfaceProfile profile, boolean analyzing) {
        TextView summary = findViewById(R.id.detail_surface_summary);
        if (profile == null) {
            summary.setText(analyzing
                    ? R.string.surface_analyzing : R.string.surface_unavailable);
            summary.setContentDescription(null);
            return;
        }
        int[] percentages = profile.percentages();
        int visible = 0;
        for (SurfaceProfile.Category category : SurfaceProfile.Category.values()) {
            if (percentages[category.ordinal()] > 0) {
                visible++;
            }
        }

        SpannableStringBuilder legend = new SpannableStringBuilder();
        StringBuilder description = new StringBuilder();
        int shown = 0;
        for (SurfaceProfile.Category category : SurfaceProfile.Category.values()) {
            int percentage = percentages[category.ordinal()];
            if (percentage <= 0) {
                continue;
            }
            if (shown > 0) {
                legend.append(shown == 2 && visible > 2 ? "\n" : "   ");
                description.append(", ");
            }
            int squareStart = legend.length();
            legend.append('\u25A0');
            legend.setSpan(new ForegroundColorSpan(category.color), squareStart,
                    squareStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            legend.setSpan(new RelativeSizeSpan(0.75f), squareStart,
                    squareStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            String label = surfaceLabel(category);
            legend.append(' ').append(label).append(' ')
                    .append(String.valueOf(percentage)).append('%');
            description.append(label).append(' ').append(percentage).append(" percent");
            shown++;
        }
        summary.setText(legend);
        summary.setContentDescription(description);
    }

    private String surfaceLabel(SurfaceProfile.Category category) {
        switch (category) {
            case TARMAC:
                return getString(R.string.surface_tarmac);
            case PAVED:
                return getString(R.string.surface_paved);
            case GRAVEL:
                return getString(R.string.surface_gravel);
            case DIRT:
                return getString(R.string.surface_dirt);
            default:
                return getString(R.string.surface_unknown);
        }
    }

    private void maybeConfirmSaveSurface() {
        if (surfaceProfile == null || sourceHasSurfaceProfile || surfaceSaveOffered
                || reversed || sourceUpdateDialog != null || sourceUpdateInProgress
                || isFinishing()) {
            return;
        }
        surfaceSaveOffered = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.save_surface_title)
            .setMessage(R.string.save_surface_message)
                .setPositiveButton(R.string.save, (ignored, which) -> saveSurfaceProfile())
                .setNegativeButton(R.string.not_now, null)
                .create();
        sourceUpdateDialog = dialog;
        dialog.setOnDismissListener(ignored -> sourceUpdateDialog = null);
        dialog.show();
    }

    private void saveSurfaceProfile() {
        if (sourceBytes == null || surfaceProfile == null || reversed) {
            return;
        }
        sourceUpdateInProgress = true;
        setActionsEnabled(false);
        findViewById(R.id.detail_progress).setVisibility(View.VISIBLE);
        byte[] originalBytes = sourceBytes;
        SurfaceProfile profileToSave = surfaceProfile;
        executor.execute(() -> {
            try {
                byte[] updated = GpxSurfaceWriter.write(originalBytes, profileToSave);
                try (OutputStream output = getContentResolver()
                        .openOutputStream(sourceUri, "wt")) {
                    if (output == null) {
                        throw new IOException("The storage provider refused write access.");
                    }
                    output.write(updated);
                }
                Uri cached = cacheHandoff(displayName, updated);
                runOnUiThread(() -> {
                    sourceBytes = updated;
                    shareableUri = cached;
                    sourceHasSurfaceProfile = true;
                    sourceUpdateInProgress = false;
                    findViewById(R.id.detail_progress).setVisibility(View.GONE);
                    setActionsEnabled(true);
                    toast(getString(R.string.surface_saved));
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not save surface data to " + sourceUri, e);
                runOnUiThread(() -> {
                    sourceUpdateInProgress = false;
                    findViewById(R.id.detail_progress).setVisibility(View.GONE);
                    setActionsEnabled(true);
                    toast(getString(R.string.surface_save_failed));
                });
            }
        });
    }

    private String summaryOf(Route route) {
        String distance = String.format(Locale.getDefault(), "%.1f km",
                route.distanceMeters / 1000.0);
        if (!route.hasElevation()) {
            return distance;
        }
        return String.format(Locale.getDefault(), "%s   ↑ %.0f m   ↓ %.0f m",
                distance, route.ascentMeters(), route.descentMeters());
    }

    private void centerCurrentLocation() {
        TrackMapView map = findViewById(R.id.detail_track_outline);
        if (!map.centerOnCurrentPosition()) {
            toast(getString(R.string.current_location_unavailable));
        }
    }

    private void showGpxInformation() {
        if (parsed == null) {
            return;
        }
        Route route = parsed.original;
        String name = parsed.name != null
                ? parsed.name : SavedRoutesActivity.readableName(displayName);
        String state = reversed ? getString(R.string.reversed) : "Original";
        String kind = parsed.waypointsAreRoute ? "Route" : "Track";
        String type = parsed.typeHint == null || parsed.typeHint.trim().isEmpty()
                ? "Not specified" : parsed.typeHint;
        String duration = route.durationSeconds > 0
                ? formatDuration(route.durationSeconds) : "Not recorded";
        String elevation = route.hasElevation()
                ? String.format(Locale.getDefault(), "%.0f–%.0f m",
                        route.minEle(), route.maxEle())
                : "Not recorded";
        LatLng start = route.track.get(0);
        LatLng finish = route.track.get(route.track.size() - 1);
        String details = String.format(Locale.getDefault(),
            "Track name: %s\n\nFile: %s\nSize: %s\nState: %s\nGPX content: %s\nType: %s\n\n"
                + "Distance: %.2f km\nDuration: %s\nTrack points: %,d\nWaypoints: %,d\n"
                + "Elevation: %s\nAscent: %.0f m\nDescent: %.0f m\n\n"
                + "Start: %.6f, %.6f\nFinish: %.6f, %.6f%s",
            name, displayName, formatFileSize(sourceBytes == null ? 0 : sourceBytes.length),
            state, kind, type, route.distanceMeters / 1000.0, duration,
                route.track.size(), parsed.waypoints.size(), elevation,
                route.ascentMeters(), route.descentMeters(), start.lat, start.lon,
                finish.lat, finish.lon, parsed.truncated
                        ? "\n\nTrack was truncated at the 50,000-point import limit." : "");
        new AlertDialog.Builder(this)
            .setTitle(R.string.gpx_information)
                .setMessage(details)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private String formatDuration(double seconds) {
        long totalMinutes = Math.round(seconds / 60.0);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours > 0
                ? String.format(Locale.getDefault(), "%d h %02d min", hours, minutes)
                : String.format(Locale.getDefault(), "%d min", minutes);
    }

    private String formatFileSize(int bytes) {
        if (bytes < 1024) {
            return String.format(Locale.getDefault(), "%d B", bytes);
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void openExternal() {
        if (shareableUri == null) {
            return;
        }
        String exportedName = exportedDisplayName();
        Intent view = GpxFiles.viewIntent(shareableUri);
        Settings settings = new Settings(this);
        ComponentName target = settings.postAction() == Settings.PostAction.DIRECT
                ? settings.directComponent() : null;
        if (target != null) {
            view.setComponent(target);
        }
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            startActivity(GpxFiles.openChooser(this, shareableUri, exportedName, null));
        }
    }

    private void handoffIfRequested() {
        if (!handoffRequested || handoffStarted || shareableUri == null) {
            return;
        }
        handoffStarted = true;
        Settings settings = new Settings(this);
        if (settings.postAction() == Settings.PostAction.SHARE) {
            share();
        } else {
            openExternal();
        }
    }

    private Uri cacheHandoff(String name, byte[] bytes) throws IOException {
        return GpxFiles.cacheCopy(this, name, GpxHandoffWriter.write(bytes));
    }

    private void share() {
        if (shareableUri != null) {
            startActivity(GpxFiles.shareChooser(this, shareableUri, exportedDisplayName()));
        }
    }

    private void reroute() {
        Uri currentRoute = shareableUri != null ? shareableUri : sourceUri;
        startActivity(MainActivity.intentForReroute(this, currentRoute));
    }

    private String exportedDisplayName() {
        return exportedDisplayName(reversed);
    }

    private String exportedDisplayName(boolean reversedState) {
        if (!reversedState) {
            return displayName;
        }
        int extension = displayName.toLowerCase(Locale.US).lastIndexOf(".gpx");
        if (extension == displayName.length() - 4) {
            return displayName.substring(0, extension)
                    + " - reverse" + displayName.substring(extension);
        }
        return displayName + " - reverse.gpx";
    }

    private void confirmDelete() {
        if (deleting) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_route_title)
                .setMessage(getString(R.string.delete_route_message,
                        SavedRoutesActivity.readableName(displayName)))
                .setPositiveButton(R.string.delete, (dialog, which) -> deleteRoute())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteRoute() {
        deleting = true;
        setActionsEnabled(false);
        findViewById(R.id.detail_progress).setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                if (!DocumentsContract.deleteDocument(getContentResolver(), sourceUri)) {
                    throw new IOException("The storage provider did not delete the file.");
                }
                RouteTileCache.delete(this, sourceUri.toString());
                SurfaceCache.delete(this, sourceUri.toString());
                runOnUiThread(() -> {
                    toast(getString(R.string.route_deleted));
                    finish();
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not delete " + sourceUri, e);
                runOnUiThread(() -> {
                    deleting = false;
                    findViewById(R.id.detail_progress).setVisibility(View.GONE);
                    setActionsEnabled(true);
                    toast(getString(R.string.delete_route_failed));
                });
            }
        });
    }

    private void reverse() {
        if (sourceBytes == null) {
            return;
        }
        setActionsEnabled(false);
        findViewById(R.id.detail_progress).setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                byte[] reversed = GpxReverser.reverse(sourceBytes);
                GpxReader.Parsed reversedParsed = enrichElevation(GpxReader.read(
                    new ByteArrayInputStream(reversed)));
                boolean nextReversed = !this.reversed;
                String reversedDisplayName = exportedDisplayName(nextReversed);
                Uri reversedUri = GpxFiles.cacheCopy(this, reversedDisplayName, reversed);
                runOnUiThread(() -> {
                    this.reversed = nextReversed;
                    if (surfaceProfile != null) {
                        surfaceProfile = surfaceProfile.reversed();
                    }
                    updateRoute(reversed, reversedParsed, reversedUri);
                    maybeConfirmSaveSurface();
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not reverse GPX", e);
                runOnUiThread(() -> {
                    findViewById(R.id.detail_progress).setVisibility(View.GONE);
                    setActionsEnabled(true);
                    toast(e.getMessage());
                });
            }
        });
    }

    private void setActionsEnabled(boolean enabled) {
        findViewById(R.id.detail_open).setEnabled(enabled);
        findViewById(R.id.detail_share).setEnabled(enabled);
        findViewById(R.id.detail_reroute).setEnabled(enabled);
        findViewById(R.id.detail_reverse).setEnabled(enabled);
        findViewById(R.id.detail_delete).setEnabled(enabled && !deleting);
    }

    private void showError(String error) {
        findViewById(R.id.detail_progress).setVisibility(View.GONE);
        TextView errorView = findViewById(R.id.detail_error);
        errorView.setText(error != null ? error : "Could not read that GPX file");
        errorView.setVisibility(View.VISIBLE);
    }

    private void startLocationTracking() {
        if (parsed == null || !hasLocationPermission()) {
            if (parsed != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !locationPermissionRequested) {
                locationPermissionRequested = true;
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_LOCATION_PERMISSION);
            }
            return;
        }
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        try {
            Location latest = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER}) {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000, 2, this);
                    Location known = locationManager.getLastKnownLocation(provider);
                    if (known != null && (latest == null || known.getTime() > latest.getTime())) {
                        latest = known;
                    }
                }
            }
            if (latest != null) {
                onLocationChanged(latest);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission was revoked", e);
        }
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (parsed == null) {
            return;
        }
        LatLng currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
        TrackPosition position = TrackPosition.nearest(parsed.original.track,
            currentLocation);
        TrackPosition routePosition = position != null
            && position.distanceFromTrackMeters <= MAX_PROFILE_DISTANCE_FROM_TRACK_METERS
            ? position : null;
        ((TrackMapView) findViewById(R.id.detail_track_outline))
            .setCurrentPosition(currentLocation, routePosition);
        ((ElevationProfileView) findViewById(R.id.detail_elevation_profile))
            .setCurrentPosition(routePosition);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION && hasLocationPermission()) {
            startLocationTracking();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ((TrackMapView) findViewById(R.id.detail_track_outline)).onResume();
        if (parsed != null) {
            startLocationTracking();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ((TrackMapView) findViewById(R.id.detail_track_outline)).onStart();
    }

    @Override
    protected void onPause() {
        ((TrackMapView) findViewById(R.id.detail_track_outline)).onPause();
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException e) {
                Log.w(TAG, "Could not stop location updates", e);
            }
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        ((TrackMapView) findViewById(R.id.detail_track_outline)).onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ((TrackMapView) findViewById(R.id.detail_track_outline)).onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        ((TrackMapView) findViewById(R.id.detail_track_outline)).onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        ((TrackMapView) findViewById(R.id.detail_track_outline)).onDestroy();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
package pl.net.xtech.maps2gpx;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.IntentCompat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "Maps2Gpx";
    private static final String EXTRA_PROMPT_REROUTE = "prompt_reroute";
    private static final int REQUEST_PICK_FOLDER = 1;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private enum PendingImportAction {
        VIEW,
        SEND_WITH_ELEVATION
    }

    /**
     * What a conversion runs on. Two kinds, because there are two entry points: a Google Maps
     * link whose track has to be built from scratch, or an existing GPX file to re-route.
     */
    private static final class Source {
        /** The shared text or link. Null for a GPX file. */
        final String link;
        /** The GPX file. Null for a link. */
        final Uri gpxUri;
        /** Its name, resolved on the first read. Touched only from the conversion thread. */
        String gpxName;
        /**
         * The travel mode the user picked for this file, or null to let the file decide. Only
         * meaningful for a GPX source - a Maps link states its own mode.
         */
        String travelMode;
        /**
         * The file's bytes, cached on the first read. A re-route can happen minutes later, by
         * which time the URI grant that arrived with the intent may be gone - the bytes are not.
         * Bytes rather than a String because the XML declaration may name another encoding.
         */
        byte[] gpxBytes;

        static Source ofLink(String link) {
            return new Source(link, null);
        }

        static Source ofGpx(Uri uri) {
            return new Source(null, uri);
        }

        private Source(String link, Uri gpxUri) {
            this.link = link;
            this.gpxUri = gpxUri;
        }

        boolean isGpx() {
            return gpxUri != null;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Button shareButton;
    private Button openButton;
    private Button folderButton;
    private Button directButton;
    private TextView folderValue;
    private TextView directValue;
    private RadioGroup actionGroup;
    private RadioGroup engineGroup;
    private RadioGroup gpxTravelModeGroup;
    private TextView engineHint;
    private CheckBox autoOpenBox;
    private CheckBox autoCloseBox;
    private ProgressBar progressBar;
    private ScrollView logScroll;
    private TextView logView;
    private TextView splashStatus;
    private TextView splashBackend;

    private OutputFolder outputFolder;
    private Settings settings;
    private Maps2Gpx.Result result;
    /** The file autosaved into shared storage - this is what gets shared. */
    private Uri savedUri;
    private String savedName;
    /** Set when a conversion finished before a folder was chosen. */
    private boolean exportPending;
    /** Source GPX waiting to be copied into the Library after folder selection. */
    private Source importPending;
    private PendingImportAction importPendingAction;
    /**
     * True while showing the spinner-only screen: launched as a share/view target with a
     * direct-open app configured, so there is nothing to decide and nothing to read.
     */
    private boolean splashMode;
    /** The log is kept here so it survives the swap from splash to the full UI. */
    private final StringBuilder logBuffer = new StringBuilder();

    /**
     * How long the splash is held open when it needs no input, so the disclaimer can
     * actually be read before the target app takes over the screen.
     */
    private static final long MIN_SPLASH_MS = 3000;
    /** When the no-input splash appeared, or 0 when the user was asked something. */
    private long splashStartedAtMs;
    /** What this launch carried, so it survives a swap to the full UI - and a re-route. */
    private Source pendingSource;
    /** A direct-open scheduled behind the splash hold, cancellable if the user steps out. */
    private Runnable pendingLaunch;
    /**
     * Set when the user pressed "Convert to GPX and open", which overrides the auto-open
     * setting for that one conversion. Reset on every conversion, so it cannot leak into a
     * later reroute.
     */
    private boolean forceOpen;
    /** True only when the user explicitly chose the plain Convert to GPX command. */
    private boolean openInViewer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        outputFolder = new OutputFolder(this);
        settings = new Settings(this);

        Source incoming = incomingSource(getIntent());
        boolean promptReroute = incoming != null
            && getIntent().getBooleanExtra(EXTRA_PROMPT_REROUTE, false);
        // Straight-through case: something to convert arrived and the user already said which
        // app to open it in, so show a spinner rather than a UI nobody needs to touch.
        splashMode = incoming != null && (incoming.isGpx() || promptReroute
            || settings.postAction() == Settings.PostAction.DIRECT
            && settings.directComponent() != null);

        pendingSource = incoming;

        if (splashMode) {
            setContentView(R.layout.activity_splash);
            splashStatus = findViewById(R.id.splash_status);
            splashBackend = findViewById(R.id.splash_backend);
            tintCompoundIcons(findViewById(R.id.splash_disclaimer));
            setDisclaimerFor(incoming);
            findViewById(R.id.splash_settings).setOnClickListener(v -> leaveSplash());
            Button openOriginal = findViewById(R.id.surface_open_original);
            Button sendOriginal = findViewById(R.id.surface_send_original);
            boolean canUseOriginal = incoming != null && incoming.isGpx();
            openOriginal.setVisibility(canUseOriginal ? View.VISIBLE : View.GONE);
            openOriginal.setOnClickListener(canUseOriginal
                    ? v -> openOriginalGpx(incoming) : null);
            sendOriginal.setVisibility(canUseOriginal ? View.VISIBLE : View.GONE);
            sendOriginal.setOnClickListener(canUseOriginal
                    ? v -> sendOriginalWithElevation(incoming) : null);
            // Before the surface is settled, name the backend only - the prompt is where
            // the surface is being chosen, so echoing it back there would be noise.
            showBackendInfo(false);
        } else {
            bindFullUi();
        }

        if (incoming == null) {
            return;
        }
        if (incoming.isGpx()) {
            promptForReroute(incoming);
            return;
        }
        // Valhalla's surface preference changes the route, so it has to be settled before
        // routing rather than after.
        if (splashMode && needsPrompt(settings.routingEngine())) {
            // The prompt already keeps the splash up for as long as the user needs.
            promptForChoice(incoming);
        } else {
            if (splashMode) {
                splashStartedAtMs = SystemClock.elapsedRealtime();
                showBackendInfo(true);
            }
            startConversion(incoming);
        }
    }

    /**
     * Paints the warning triangle in the note's own text colour. Done in code rather than
     * with android:drawableTint, which needs API 23, so it also matches on API 21-22.
     */
    private void tintCompoundIcons(TextView note) {
        if (note == null) {
            return;
        }
        // Position-agnostic: tint whichever slot the icon sits in, so moving it between
        // drawableTop and drawableStart in the layout cannot silently stop the tinting.
        Drawable[] slots = note.getCompoundDrawablesRelative();
        boolean tinted = false;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                slots[i] = slots[i].mutate();
                slots[i].setTint(note.getCurrentTextColor());
                tinted = true;
            }
        }
        if (tinted) {
            note.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    slots[0], slots[1], slots[2], slots[3]);
        }
    }

    /**
     * The accuracy note differs by entry point. A shared link never carried any geometry; a GPX
     * file did, and re-routing deliberately replaces it. Telling someone re-routing a file that
     * "the shared link contains only the stops" would be describing a different operation.
     */
    private void setDisclaimerFor(Source source) {
        TextView note = findViewById(R.id.splash_disclaimer);
        if (note == null || source == null) {
            return;
        }
        note.setText(source.isGpx()
                ? R.string.splash_disclaimer_gpx : R.string.splash_disclaimer);
    }

    /**
     * Names the backend on the splash, and once the surface is settled, how that backend is
     * configured. The travel mode is not known until the link has been resolved, so the
     * cycling mapping is what gets shown - it is the only mode the surface affects.
     */
    private void showBackendInfo(boolean choiceSettled) {
        if (splashBackend == null) {
            return;
        }
        Settings.RoutingEngine engine = settings.routingEngine();
        StringBuilder info = new StringBuilder("Routing via ").append(engine.label);

        if (choiceSettled) {
            if (engine == Settings.RoutingEngine.BROUTER) {
                Settings.BRouterProfile profile = settings.brouterProfile();
                info.append("\nProfile: ").append(profile.label)
                        .append(" (").append(profile.file).append(".brf)");
            } else if (engine == Settings.RoutingEngine.VALHALLA) {
                info.append("\nSurface: ").append(settings.surface().label);
            }
        }
        if (pendingSource != null && pendingSource.isGpx()) {
            info.append("\nTravel type: ").append(settings.gpxTravelMode().label);
        }
        if (engine == Settings.RoutingEngine.BROUTER) {
            info.append(BRouterRouter.isInstalled(this)
                    ? "\nOffline — needs BRouter's segments for this area"
                    : "\nBRouter app not installed — will fall back to an online engine");
        }
        splashBackend.setText(info);
    }

    /**
     * Whether the engine takes a per-conversion choice worth prompting for: Valhalla a
     * surface preference, BRouter a named profile. OSRM takes neither.
     */
    private static boolean needsPrompt(Settings.RoutingEngine engine) {
        return engine == Settings.RoutingEngine.VALHALLA
                || engine == Settings.RoutingEngine.BROUTER;
    }

    /**
     * What to do with the index the user picked from a splash prompt.
     *
     * @param openAfter true when the user asked to hand the route straight on, overriding the
     *                  auto-open setting for this conversion only
     */
    private interface ChoiceHandler {
        void onChosen(int index, boolean openAfter);
    }

    /**
     * Generic radio-list face of the splash. Shared by the pre-conversion prompt and the
     * reroute flow, so both look and behave identically.
     *
     * @param offerOpen whether to show the "and open" button. False for an intermediate step
     *                  such as picking the engine, which is not the thing that starts a
     *                  conversion.
     */
    private void showOptionPrompt(int titleRes, String subtitleText, String[] labels,
                                  int selected, int primaryLabelRes, boolean offerOpen,
                                  Source source, ChoiceHandler handler) {
        View promptGroup = findViewById(R.id.surface_group);
        RadioGroup options = findViewById(R.id.surface_options);
        TextView title = findViewById(R.id.surface_title);
        TextView subtitle = findViewById(R.id.surface_subtitle);
        Button go = findViewById(R.id.surface_go);
        Button goAndOpen = findViewById(R.id.surface_go_open);
        if (promptGroup == null || options == null) {
            return;
        }

        setSplashFace(promptGroup);
        title.setText(titleRes);
        subtitle.setText(subtitleText);

        options.setVisibility(View.VISIBLE);
        options.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(i);
            button.setText(labels[i]);
            options.addView(button);
        }
        int initial = Math.max(0, Math.min(selected, labels.length - 1));
        options.check(initial);

        go.setText(primaryLabelRes);
        go.setOnClickListener(v -> handler.onChosen(checkedIndex(options, labels, initial), false));

        goAndOpen.setVisibility(offerOpen ? View.VISIBLE : View.GONE);
        goAndOpen.setOnClickListener(offerOpen
                ? v -> handler.onChosen(checkedIndex(options, labels, initial), true)
                : null);
        configureGpxActionButtons(source);
    }

    private int checkedIndex(RadioGroup options, String[] labels, int fallback) {
        int checked = options.getCheckedRadioButtonId();
        return checked >= 0 && checked < labels.length ? checked : fallback;
    }

    /** Shows exactly one of the splash's prompt and progress faces. */
    private void setSplashFace(View wanted) {
        for (int id : new int[]{R.id.spinner_group, R.id.surface_group}) {
            View face = findViewById(id);
            if (face != null) {
                face.setVisibility(face == wanted ? View.VISIBLE : View.GONE);
            }
        }
    }

    /** Pick what the current engine acts on - a BRouter profile, or a surface - then convert. */
    private void promptForChoice(Source source) {
        if (settings.routingEngine() == Settings.RoutingEngine.BROUTER) {
            Settings.BRouterProfile[] all = Settings.BRouterProfile.values();
            showOptionPrompt(R.string.profile_title,
                    "BRouter routing profile. Determines surface, gradient and traffic "
                            + "preferences.",
                    labelsOf(all), settings.brouterProfile().ordinal(),
                        R.string.convert, true, source,
                    (index, openAfter) -> {
                        settings.setBrouterProfile(all[index]);
                        beginConversion(source, openAfter);
                    });
        } else {
            Settings.Surface[] all = Settings.Surface.values();
            showOptionPrompt(R.string.surface_title,
                    "Applies to cycling routes. Ignored for driving and walking.",
                    labelsOf(all), settings.surface().ordinal(),
                    R.string.convert, true, source,
                    (index, openAfter) -> {
                        settings.setSurface(all[index]);
                        beginConversion(source, openAfter);
                    });
        }
    }

    /** Uses the persistent GPX travel type and backend, then asks only route-specific options. */
    private void promptForReroute(Source source) {
        if (source.isGpx()) {
            source.travelMode = settings.gpxTravelMode().value;
        }
        if (needsPrompt(settings.routingEngine())) {
            promptForChoice(source);
        } else {
            showConfiguredConversionPrompt(source);
        }
    }

    private void showConfiguredConversionPrompt(Source source) {
        View promptGroup = findViewById(R.id.surface_group);
        RadioGroup options = findViewById(R.id.surface_options);
        TextView title = findViewById(R.id.surface_title);
        TextView subtitle = findViewById(R.id.surface_subtitle);
        Button go = findViewById(R.id.surface_go);
        Button goAndOpen = findViewById(R.id.surface_go_open);
        if (promptGroup == null || options == null) {
            return;
        }
        setSplashFace(promptGroup);
        title.setText(R.string.reroute_gpx);
        subtitle.setText("Using " + settings.gpxTravelMode().label + " via "
                + settings.routingEngine().label + ". Change these defaults in Settings.");
        options.removeAllViews();
        options.setVisibility(View.GONE);
        go.setText(R.string.convert);
        go.setOnClickListener(v -> beginConversion(source, false));
        goAndOpen.setVisibility(View.VISIBLE);
        goAndOpen.setOnClickListener(v -> beginConversion(source, true));
        configureGpxActionButtons(source);
    }

    private void configureGpxActionButtons(Source source) {
        if (source == null || !source.isGpx()) {
            return;
        }
        Button reroute = findViewById(R.id.surface_go);
        Button sendTo = findViewById(R.id.surface_go_open);
        Button viewTrack = findViewById(R.id.surface_open_original);
        Button sendOriginal = findViewById(R.id.surface_send_original);
        reroute.setText(R.string.reroute_action);
        viewTrack.setText(R.string.view_track);
        setInlineTargetIcon(sendTo, R.string.send_to);
        setInlineTargetIcon(sendOriginal, R.string.send_original_to);
    }

    private void setInlineTargetIcon(Button button, int labelRes) {
        Drawable icon = settings.postAction() == Settings.PostAction.DIRECT
                ? directAppIcon() : null;
        if (icon == null) {
            icon = getDrawable(R.drawable.ic_open_external);
        }
        if (icon != null) {
            int size = Math.round(22 * getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
        }
        SpannableStringBuilder label = new SpannableStringBuilder(getString(labelRes));
        if (icon != null) {
            label.append(' ');
            int iconStart = label.length();
            label.append('\uFFFC');
            label.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BASELINE),
                    iconStart, iconStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setCompoundDrawablesRelative(null, null, null, null);
        button.setText(label);
    }

    private void openOriginalGpx(Source source) {
        if (source == null || !source.isGpx()) {
            return;
        }
        Uri tree = outputFolder.treeUri();
        if (tree == null) {
            importPending = source;
            importPendingAction = PendingImportAction.VIEW;
            appendLog("Choose the Library folder before importing this GPX…");
            pickFolder();
            return;
        }

        View spinner = findViewById(R.id.spinner_group);
        if (spinner != null) {
            setSplashFace(spinner);
        }
        if (splashStatus != null) {
            splashStatus.setText("Importing GPX and checking road surfaces…");
        }
        executor.execute(() -> {
            try {
                if (source.gpxName == null) {
                    source.gpxName = GpxFiles.displayNameOf(this, source.gpxUri);
                }
                if (source.gpxBytes == null) {
                    source.gpxBytes = GpxFiles.readAll(this, source.gpxUri);
                }
                GpxReader.Parsed imported = GpxReader.read(
                        new ByteArrayInputStream(source.gpxBytes));
                SurfaceProfile importedSurface = imported.surfaceProfile;
                if (importedSurface == null) {
                    try {
                        importedSurface = SurfaceAnalyzer.fetch(imported.original.track);
                    } catch (IOException | RuntimeException ignored) {
                        // Import remains useful without surfaces; the viewer can retry later.
                    }
                }
                String fileName = source.gpxName.toLowerCase(Locale.US).endsWith(".gpx")
                        ? source.gpxName : source.gpxName + ".gpx";
                OutputFolder.Saved saved = outputFolder.save(
                        tree, fileName, source.gpxBytes);
                if (importedSurface != null) {
                    SurfaceCache.save(this, saved.uri.toString(), imported.original.track,
                            importedSurface);
                }
                runOnUiThread(() -> {
                    startActivity(SavedRouteDetailActivity.intentFor(
                            this, saved.uri, saved.displayName));
                    finish();
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    toast("Could not import GPX into the Library");
                    appendLog("Import failed: " + e.getMessage());
                    promptForReroute(source);
                });
            }
        });
    }

    private void sendOriginalWithElevation(Source source) {
        if (source == null || !source.isGpx()) {
            return;
        }
        Uri tree = outputFolder.treeUri();
        if (tree == null) {
            importPending = source;
            importPendingAction = PendingImportAction.SEND_WITH_ELEVATION;
            appendLog("Choose the Library folder before sending this GPX…");
            pickFolder();
            return;
        }

        View spinner = findViewById(R.id.spinner_group);
        if (spinner != null) {
            setSplashFace(spinner);
        }
        if (splashStatus != null) {
            splashStatus.setText("Checking GPX elevation…");
        }
        executor.execute(() -> {
            try {
                if (source.gpxName == null) {
                    source.gpxName = GpxFiles.displayNameOf(this, source.gpxUri);
                }
                if (source.gpxBytes == null) {
                    source.gpxBytes = GpxFiles.readAll(this, source.gpxUri);
                }
                GpxReader.Parsed parsed = GpxReader.read(
                        new ByteArrayInputStream(source.gpxBytes));
                byte[] updated = source.gpxBytes;
                if (!parsed.original.hasElevation()) {
                    List<LatLng> elevated = Elevation.fill(parsed.original.track);
                    Route elevatedRoute = new Route(elevated, parsed.original.distanceMeters,
                            parsed.original.durationSeconds, parsed.original.profile,
                            parsed.original.profileTag);
                    if (!elevatedRoute.hasElevation()) {
                        throw new IOException("The elevation service returned no data.");
                    }
                    updated = GpxElevationWriter.write(source.gpxBytes, elevated);
                }
                String fileName = source.gpxName.toLowerCase(Locale.US).endsWith(".gpx")
                        ? source.gpxName : source.gpxName + ".gpx";
                OutputFolder.Saved saved = outputFolder.save(tree, fileName, updated);
                runOnUiThread(() -> sendSavedOriginal(source, saved));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    toast("Could not prepare GPX with elevation");
                    appendLog("Send original failed: " + e.getMessage());
                    promptForReroute(source);
                });
            }
        });
    }

    private void sendSavedOriginal(Source source, OutputFolder.Saved saved) {
        savedUri = saved.uri;
        savedName = saved.displayName;
        showConfiguredConversionPrompt(source);
        dispatchPostAction();
    }

    private String[] labelsOf(Object[] values) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Settings.BRouterProfile) {
                labels[i] = ((Settings.BRouterProfile) values[i]).label;
            } else {
                labels[i] = ((Settings.Surface) values[i]).label;
            }
        }
        return labels;
    }

    private void beginConversion(Source source, boolean openWhenDone) {
        forceOpen = openWhenDone;
        openInViewer = !openWhenDone;
        showBackendInfo(true);
        View spinner = findViewById(R.id.spinner_group);
        if (spinner != null) {
            setSplashFace(spinner);
        }
        startConversion(source);
    }

    private void bindFullUi() {
        setContentView(R.layout.activity_main);
        // Reached as Settings from the home screen now, rather than being the app's front door.
        setTitle(R.string.settings);
        splashStatus = null;

        shareButton = findViewById(R.id.share_button);
        openButton = findViewById(R.id.open_button);
        folderButton = findViewById(R.id.folder_button);
        directButton = findViewById(R.id.direct_button);
        folderValue = findViewById(R.id.folder_value);
        directValue = findViewById(R.id.direct_value);
        actionGroup = findViewById(R.id.action_group);
        progressBar = findViewById(R.id.progress);
        logScroll = findViewById(R.id.log_scroll);
        logView = findViewById(R.id.log_view);

        shareButton.setOnClickListener(v -> shareResult());
        openButton.setOnClickListener(v -> openResult());
        folderButton.setOnClickListener(v -> pickFolder());
        directButton.setOnClickListener(v -> chooseDirectApp());

        actionGroup.setOnCheckedChangeListener((group, id) -> {
            settings.setPostAction(actionFor(id));
            refreshSettingsUi();
        });

        autoOpenBox = findViewById(R.id.auto_open);
        autoOpenBox.setOnCheckedChangeListener((button, checked) -> {
            settings.setAutoOpen(checked);
            refreshSettingsUi();
        });

        autoCloseBox = findViewById(R.id.auto_close);
        autoCloseBox.setOnCheckedChangeListener(
                (button, checked) -> settings.setAutoCloseAfterOpen(checked));

        engineGroup = findViewById(R.id.engine_group);
        gpxTravelModeGroup = findViewById(R.id.gpx_travel_mode_group);
        engineHint = findViewById(R.id.engine_hint);
        gpxTravelModeGroup.setOnCheckedChangeListener((group, id) ->
            settings.setGpxTravelMode(gpxTravelModeFor(id)));
        engineGroup.setOnCheckedChangeListener((group, id) -> {
            Settings.RoutingEngine chosen = engineFor(id);
            settings.setRoutingEngine(chosen);
            refreshSettingsUi();
            if (chosen == Settings.RoutingEngine.BROUTER && !BRouterRouter.isInstalled(this)) {
                promptToInstallBRouter();
            }
        });

        logView.setText(logBuffer);
        refreshSettingsUi();
    }

    /**
     * Drops out of splash mode into the full UI - used when something needs the user's
     * attention after all (an error, or no output folder chosen yet).
     */
    private void leaveSplash() {
        if (!splashMode) {
            return;
        }
        splashMode = false;
        // No longer a spinner nobody can read, so drop the artificial hold.
        splashStartedAtMs = 0;
        if (pendingLaunch != null) {
            // The user deliberately stepped out of the splash; yanking them into the target
            // app a second later would undo that. The file is already saved either way.
            mainHandler.removeCallbacks(pendingLaunch);
            pendingLaunch = null;
            appendLog("Direct open cancelled - use Open or Share when you are ready.");
        }
        bindFullUi();
        if (result != null) {
            shareButton.setEnabled(true);
            openButton.setEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The chosen-app receiver writes to preferences, so re-read on the way back in.
        if (!splashMode) {
            refreshSettingsUi();
        }
    }

    private Settings.PostAction actionFor(int checkedId) {
        if (checkedId == R.id.action_share) {
            return Settings.PostAction.SHARE;
        }
        if (checkedId == R.id.action_direct) {
            return Settings.PostAction.DIRECT;
        }
        return Settings.PostAction.VIEW;
    }

    private int radioFor(Settings.PostAction action) {
        switch (action) {
            case SHARE:
                return R.id.action_share;
            case DIRECT:
                return R.id.action_direct;
            default:
                return R.id.action_view;
        }
    }

    private Settings.RoutingEngine engineFor(int checkedId) {
        if (checkedId == R.id.engine_valhalla) {
            return Settings.RoutingEngine.VALHALLA;
        }
        if (checkedId == R.id.engine_brouter) {
            return Settings.RoutingEngine.BROUTER;
        }
        return Settings.RoutingEngine.OSRM;
    }

    private Settings.TravelMode gpxTravelModeFor(int checkedId) {
        if (checkedId == R.id.gpx_travel_walking) {
            return Settings.TravelMode.WALKING;
        }
        if (checkedId == R.id.gpx_travel_driving) {
            return Settings.TravelMode.DRIVING;
        }
        return Settings.TravelMode.CYCLING;
    }

    private int radioFor(Settings.TravelMode travelMode) {
        switch (travelMode) {
            case WALKING:
                return R.id.gpx_travel_walking;
            case DRIVING:
                return R.id.gpx_travel_driving;
            case CYCLING:
            default:
                return R.id.gpx_travel_cycling;
        }
    }

    private int radioFor(Settings.RoutingEngine engine) {
        switch (engine) {
            case VALHALLA:
                return R.id.engine_valhalla;
            case BROUTER:
                return R.id.engine_brouter;
            case OSRM:
            default:
                return R.id.engine_osrm;
        }
    }

    /** BRouter is a separate app, so offer to fetch it rather than just failing later. */
    private void promptToInstallBRouter() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.brouter_missing_title)
                .setMessage(R.string.brouter_missing_message)
                .setPositiveButton(R.string.install, (dialog, which) -> openBRouterListing())
                .setNegativeButton(R.string.use_online, (dialog, which) -> {
                    settings.setRoutingEngine(Settings.RoutingEngine.OSRM);
                    refreshSettingsUi();
                })
                .show();
    }

    private void openBRouterListing() {
        try {
            startActivity(BRouterRouter.installIntent());
        } catch (RuntimeException noPlayStoreApp) {
            try {
                startActivity(BRouterRouter.installIntentFallback());
            } catch (RuntimeException noBrowser) {
                appendLog("Could not open the Play Store: " + noBrowser.getMessage());
                toast("No Play Store or browser available");
            }
        }
    }

    private void refreshSettingsUi() {
        int wantedTravelMode = radioFor(settings.gpxTravelMode());
        if (gpxTravelModeGroup.getCheckedRadioButtonId() != wantedTravelMode) {
            gpxTravelModeGroup.check(wantedTravelMode);
        }
        Settings.RoutingEngine engine = settings.routingEngine();
        int wantedEngine = radioFor(engine);
        if (engineGroup.getCheckedRadioButtonId() != wantedEngine) {
            engineGroup.check(wantedEngine);
        }
        switch (engine) {
            case VALHALLA:
                engineHint.setText(getString(R.string.engine_hint_valhalla)
                        + " Currently: " + settings.surface().label);
                break;
            case BROUTER:
                engineHint.setText(BRouterRouter.isInstalled(this)
                        ? getString(R.string.engine_hint_brouter_ready)
                        : getString(R.string.engine_hint_brouter_missing));
                break;
            case OSRM:
            default:
                engineHint.setText(getString(R.string.engine_hint_osrm));
                break;
        }

        Uri tree = outputFolder.treeUri();
        folderValue.setText(tree == null
                ? getString(R.string.folder_none) : outputFolder.describe(tree));

        Settings.PostAction action = settings.postAction();
        int wanted = radioFor(action);
        if (actionGroup.getCheckedRadioButtonId() != wanted) {
            actionGroup.check(wanted);
        }

        String app = settings.describeDirectComponent();
        directValue.setText(app == null ? getString(R.string.direct_none) : app);
        Drawable icon = directAppIcon();
        if (icon != null) {
            int size = Math.round(24 * getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
        }
        directValue.setCompoundDrawables(icon, null, null, null);
        directValue.setCompoundDrawablePadding(
                Math.round(8 * getResources().getDisplayMetrics().density));

        boolean direct = action == Settings.PostAction.DIRECT;
        directValue.setEnabled(direct);
        directButton.setEnabled(direct);

        if (autoOpenBox.isChecked() != settings.autoOpen()) {
            autoOpenBox.setChecked(settings.autoOpen());
        }
        if (autoCloseBox.isChecked() != settings.autoCloseAfterOpen()) {
            autoCloseBox.setChecked(settings.autoCloseAfterOpen());
        }
        // Closing after opening is meaningless if nothing opens by itself.
        autoCloseBox.setEnabled(settings.autoOpen());
    }

    private Drawable directAppIcon() {
        ComponentName component = settings.directComponent();
        if (component == null) {
            return null;
        }
        try {
            return getPackageManager().getActivityInfo(component, 0)
                    .loadIcon(getPackageManager());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lists the installed apps that can open a .gpx and lets the user pick one. Selecting
     * from our own dialog only records the choice - unlike the system chooser, it does not
     * also launch the app with a placeholder file.
     */
    private void chooseDirectApp() {
        List<ResolveInfo> matches;
        try {
            matches = getPackageManager().queryIntentActivities(
                    GpxFiles.viewIntent(GpxFiles.probeUri(this)), 0);
        } catch (RuntimeException e) {
            appendLog("Could not list GPX apps: " + e.getMessage());
            return;
        }

        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo info : matches) {
            if (!getPackageName().equals(info.activityInfo.packageName)) {
                apps.add(info);
            }
        }
        if (apps.isEmpty()) {
            appendLog("No installed app advertises that it can open a .gpx file. "
                    + "Pick \"Ask which app to open it in\" instead.");
            toast("No GPX apps found");
            return;
        }
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));

        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_app)
                .setAdapter(new AppRowAdapter(this, apps, pm), (dialog, which) -> {
                    ActivityInfo chosen = apps.get(which).activityInfo;
                    ComponentName component =
                            new ComponentName(chosen.packageName, chosen.name);
                    settings.setDirectComponent(component);
                    refreshSettingsUi();
                    appendLog("Direct-open app: " + component.flattenToShortString());
                })
                .setNeutralButton(R.string.clear, (dialog, which) -> {
                    settings.setDirectComponent(null);
                    refreshSettingsUi();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Icon + app name, so the picker looks like a normal Android app list. */
    private static final class AppRowAdapter extends ArrayAdapter<ResolveInfo> {
        private final PackageManager packageManager;

        AppRowAdapter(Context context, List<ResolveInfo> apps, PackageManager packageManager) {
            super(context, R.layout.app_row, R.id.app_label, apps);
            this.packageManager = packageManager;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = super.getView(position, convertView, parent);
            ResolveInfo info = getItem(position);
            if (info != null) {
                ((TextView) row.findViewById(R.id.app_label))
                        .setText(info.loadLabel(packageManager));
                ((ImageView) row.findViewById(R.id.app_icon))
                        .setImageDrawable(info.loadIcon(packageManager));
            }
            return row;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Source incoming = incomingSource(intent);
        if (incoming == null) {
            return;
        }
        forceOpen = false;
        openInViewer = false;
        if (incoming.isGpx()) {
            recreate();
            return;
        }
        startConversion(incoming);
    }

    static Intent intentForReroute(Context context, Uri uri) {
        return new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/gpx+xml")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_PROMPT_REROUTE, true);
    }

    /**
     * What this launch carries, or null when opened from the launcher. Four ways in:
     * "Maps -> Share -> Maps2Gpx", "open this link with Maps2Gpx", a shared {@code .gpx}, and
     * "open this .gpx with Maps2Gpx".
     */
    private Source incomingSource(Intent intent) {
        if (intent == null) {
            return null;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            // Some senders attach both; a text/plain share is a link even when it also
            // carries a stream.
            if (stream != null && !isTextShare(intent.getType())) {
                return Source.ofGpx(stream);
            }
            return asLink(intent.getStringExtra(Intent.EXTRA_TEXT));
        }
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            Uri data = intent.getData();
            String scheme = data.getScheme();
            // A local file rather than a web link: the only thing that can be is a GPX,
            // since that is all the manifest claims for content:// and file://.
            if ("content".equals(scheme) || "file".equals(scheme)) {
                return Source.ofGpx(data);
            }
            return asLink(data.toString());
        }
        return null;
    }

    private static boolean isTextShare(String mimeType) {
        return mimeType != null && mimeType.startsWith("text/");
    }

    private Source asLink(String text) {
        return text == null || text.trim().isEmpty() ? null : Source.ofLink(text.trim());
    }

    private void startConversion(Source source) {
        pendingSource = source;
        setBusy(true);
        // A reroute can start while the previous route's direct-open is still on the clock;
        // that launch would hand over the file we are about to replace.
        if (pendingLaunch != null) {
            mainHandler.removeCallbacks(pendingLaunch);
            pendingLaunch = null;
        }
        result = null;
        savedUri = null;
        savedName = null;
        exportPending = false;
        logBuffer.setLength(0);
        if (logView != null) {
            logView.setText("");
        }
        Progress progress = message -> mainHandler.post(() -> appendLog(message));

        Settings.RoutingEngine engine = settings.routingEngine();
        Settings.Surface surface = settings.surface();
        Settings.BRouterProfile brouterProfile = settings.brouterProfile();
        executor.execute(() -> {
            try {
                Maps2Gpx.Result converted = source.isGpx()
                        ? Maps2Gpx.reroute(getApplicationContext(), gpxBytesOf(source, progress),
                                source.gpxName, engine, surface, brouterProfile,
                                source.travelMode, progress)
                        : Maps2Gpx.convert(getApplicationContext(), source.link,
                                engine, surface, brouterProfile, progress);
                mainHandler.post(() -> onConverted(converted));
            } catch (IOException | RuntimeException e) {
                mainHandler.post(() -> onFailed(e));
            }
        });
    }

    /**
     * The GPX file's bytes, read once and then kept: a re-route must not depend on the URI
     * grant that arrived with the intent still being alive.
     *
     * <p>Runs on the conversion thread, which is the only thread that touches these fields.
     */
    private byte[] gpxBytesOf(Source source, Progress progress) throws IOException {
        if (source.gpxName == null) {
            source.gpxName = GpxFiles.displayNameOf(this, source.gpxUri);
        }
        if (source.gpxBytes != null) {
            return source.gpxBytes;
        }
        progress.step("Reading " + source.gpxName + "…");
        source.gpxBytes = GpxFiles.readAll(this, source.gpxUri);
        progress.step("Read " + source.gpxBytes.length + " bytes.");
        return source.gpxBytes;
    }

    private void onConverted(Maps2Gpx.Result converted) {
        result = converted;
        setBusy(false);
        appendLog("");
        appendLog("Converted " + converted.stopCount + " stops into "
                + converted.trackPointCount + " track points.");
        // Repeated at the end, and marked, so they are the last thing in the log rather than
        // buried mid-trace. The full UI has no summary panel, so this is where it sees them.
        for (String notice : converted.notices.messages) {
            appendLog("WARNING: " + notice);
        }
        if (!splashMode) {
            shareButton.setEnabled(true);
            openButton.setEnabled(true);
        }
        exportAndShare();
    }

    /**
     * The finishing move: drop the file into the folder the user chose once, then hand
     * it straight to the share sheet.
     */
    private void exportAndShare() {
        if (result == null) {
            return;
        }
        Uri tree = outputFolder.treeUri();
        if (tree == null) {
            // First run (or the grant went away) - ask once, then come back here.
            // Needs the user, so the spinner-only screen is no longer appropriate.
            leaveSplash();
            exportPending = true;
            appendLog("Choose the folder to save GPX files in…");
            pickFolder();
            return;
        }

        OutputFolder.Saved saved;
        try {
            byte[] libraryGpx = GpxHandoffWriter.write(result.gpx.getBytes(UTF8));
            saved = outputFolder.save(tree, result.fileName, libraryGpx);
        } catch (IOException | RuntimeException e) {
            leaveSplash();
            appendLog("Save failed: " + e.getMessage());
            appendLog("Pick a different folder with the Change button, or use Share.");
            toast("Save failed");
            return;
        }
        savedUri = saved.uri;
        savedName = saved.displayName;
        if (result.surfaceProfile != null) {
            SurfaceCache.save(this, savedUri.toString(), result.route.track,
                result.surfaceProfile);
        }
        appendLog("Saved " + saved.displayName + " to " + outputFolder.describe(tree));
        if (!splashMode) {
            refreshSettingsUi();
        }

        if (splashMode) {
            Intent viewer = openInViewer
                    ? SavedRouteDetailActivity.intentFor(this, savedUri, savedName)
                    : SavedRouteDetailActivity.intentForHandoff(this, savedUri, savedName);
            startActivity(viewer);
            finish();
            return;
        }

        if (openInViewer) {
            startActivity(SavedRouteDetailActivity.intentFor(this, savedUri, savedName));
            finish();
            return;
        }

        if (!settings.autoOpen() && !forceOpen) {
            // Saving still happened; only the hand-off waits for the user.
            appendLog("Auto-open is off - use Open when you are ready.");
            return;
        }
        dispatchPostAction();
    }

    /** Performs whatever "open" means for the current post-conversion setting. */
    private void dispatchPostAction() {
        switch (settings.postAction()) {
            case SHARE:
                shareResult();
                break;
            case DIRECT:
                openDirectly();
                break;
            case VIEW:
            default:
                openResult();
                break;
        }
    }

    /**
     * Opens the GPX in the one remembered app, skipping the chooser. If no app has been
     * chosen yet, shows the chooser once and remembers what the user picks.
     */
    private void openDirectly() {
        ComponentName target = settings.directComponent();
        if (target == null) {
            leaveSplash();
            appendLog("No direct-open app chosen yet - asking once.");
            openResult();
            return;
        }
        Uri uri = shareableUri();
        if (uri == null) {
            leaveSplash();
            return;
        }

        // Routing usually finishes in a couple of seconds, which is not long enough to read
        // the disclaimer before the target app covers it. Hold the rest of the three
        // seconds - and only the rest, so a slow conversion adds nothing.
        long wait = remainingSplashMs();
        if (wait > 0) {
            appendLog("Opening " + target.getPackageName() + "…");
            pendingLaunch = () -> {
                pendingLaunch = null;
                launchDirect(uri, target);
            };
            mainHandler.postDelayed(pendingLaunch, wait);
        } else {
            launchDirect(uri, target);
        }
    }

    /** Whether the current result came with something the user needs to have seen. */
    private boolean hasWarnings() {
        return result != null && !result.notices.isEmpty();
    }

    private long remainingSplashMs() {
        if (splashStartedAtMs <= 0) {
            return 0;
        }
        return Math.max(0, MIN_SPLASH_MS - (SystemClock.elapsedRealtime() - splashStartedAtMs));
    }

    private void launchDirect(Uri uri, ComponentName target) {
        if (isFinishing()) {
            return;
        }
        try {
            Intent view = GpxFiles.viewIntent(uri);
            view.setComponent(target);
            startActivity(view);
            appendLog("Opened in " + target.getPackageName());
            // Closing behind the target app is right when the conversion went as asked. When it
            // did not - offline silently became online, or roads became straight lines - closing
            // would destroy the only place that says so, so the summary is left standing.
            if (splashMode && settings.autoCloseAfterOpen() && hasWarnings()) {
                appendLog("Left open so the warning above stays readable.");
            } else if (splashMode && settings.autoCloseAfterOpen()) {
                // Nothing left to show: the target app is in front now.
                finish();
            } else if (splashMode) {
                appendLog("Left open - the route summary is on screen.");
            }
        } catch (ActivityNotFoundException e) {
            // Uninstalled or renamed since we remembered it.
            leaveSplash();
            appendLog(target.getPackageName() + " can no longer open this - asking again.");
            settings.setDirectComponent(null);
            refreshSettingsUi();
            openResult();
        } catch (RuntimeException e) {
            leaveSplash();
            appendLog("Could not open in " + target.getPackageName() + ": " + e.getMessage());
        }
    }

    private void pickFolder() {
        try {
            startActivityForResult(OutputFolder.pickIntent(), REQUEST_PICK_FOLDER);
        } catch (RuntimeException e) {
            exportPending = false;
            importPending = null;
            importPendingAction = null;
            appendLog("No folder picker available on this device: " + e.getMessage());
            toast("No folder picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FOLDER) {
            return;
        }
        boolean wasPending = exportPending;
        exportPending = false;
        Source pendingImport = importPending;
        importPending = null;
        PendingImportAction pendingAction = importPendingAction;
        importPendingAction = null;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (wasPending) {
                appendLog("No folder chosen - nothing saved. Use Share to send the GPX, "
                        + "or Folder to choose a destination.");
            } else if (pendingImport != null) {
                appendLog("No Library folder chosen - the GPX was not imported.");
            }
            return;
        }
        Uri tree = data.getData();
        outputFolder.remember(tree, data);
        appendLog("Output folder: " + outputFolder.describe(tree));
        if (wasPending) {
            exportAndShare();
        } else if (pendingImport != null) {
            if (pendingAction == PendingImportAction.SEND_WITH_ELEVATION) {
                sendOriginalWithElevation(pendingImport);
            } else {
                openOriginalGpx(pendingImport);
            }
        }
    }

    /**
     * Offers the converted route to other apps with ACTION_VIEW - see
     * {@link GpxFiles#openChooser}, which is where the reasons live now that the saved-routes
     * screen hands files over the same way.
     */
    private void openResult() {
        Uri uri = shareableUri();
        if (uri == null) {
            return;
        }
        String name = displayName();
        try {
            startActivity(GpxFiles.openChooser(this, uri, name, chosenAppSender()));
        } catch (RuntimeException e) {
            appendLog("Could not open the GPX: " + e.getMessage());
            toast("Open failed");
        }
    }

    /**
     * Lets {@link ChosenAppReceiver} learn which app the user picked, so DIRECT mode has
     * something to remember. Returns null below API 22, where the chooser cannot report
     * the choice - DIRECT then just keeps asking.
     */
    private IntentSender chosenAppSender() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return null;
        }
        Intent callback = new Intent(this, ChosenAppReceiver.class);
        callback.setAction(ChosenAppReceiver.ACTION_APP_CHOSEN);
        // FLAG_MUTABLE: the platform fills in EXTRA_CHOSEN_COMPONENT for us.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(this, 0, callback, flags).getIntentSender();
    }

    /** Explicit Share: ACTION_SEND, for messaging and cloud apps. */
    private void shareResult() {
        Uri uri = shareableUri();
        if (uri == null) {
            return;
        }
        String name = displayName();
        try {
            startActivity(GpxFiles.shareChooser(this, uri, name));
        } catch (RuntimeException e) {
            appendLog("Share failed: " + e.getMessage());
            toast("Share failed");
        }
    }

    private String displayName() {
        return savedName != null ? savedName : result.fileName;
    }

    /** The in-memory result, copied out to a hand-off file. See {@link GpxFiles#cacheCopy}. */
    private Uri shareableUri() {
        if (result == null) {
            return savedUri;
        }
        try {
            byte[] handoff = GpxHandoffWriter.write(result.gpx.getBytes(UTF8));
            return GpxFiles.cacheCopy(this, displayName(), handoff);
        } catch (IOException | RuntimeException e) {
            appendLog("Could not prepare the file for sharing: " + e.getMessage());
            toast("Share failed");
            return null;
        }
    }

    private void onFailed(Exception e) {
        // An error is worth reading, so surface the full log rather than a bare spinner.
        leaveSplash();
        setBusy(false);
        appendLog("");
        appendLog("FAILED: " + e.getMessage());
        toast("Conversion failed");
    }

    private void setBusy(boolean busy) {
        if (splashMode) {
            return;
        }
        progressBar.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        if (busy) {
            shareButton.setEnabled(false);
            openButton.setEnabled(false);
        }
    }

    private void appendLog(String message) {
        // Mirrored to logcat so the same trace is readable over adb - handy on devices
        // where uiautomator cannot dump the window.
        Log.i(TAG, message);
        logBuffer.append(message).append('\n');

        if (splashStatus != null && !message.trim().isEmpty()) {
            splashStatus.setText(message.trim());
        }
        if (logView != null) {
            logView.append(message + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}

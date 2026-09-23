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

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "Maps2Gpx";
    private static final int REQUEST_PICK_FOLDER = 1;
    private static final Charset UTF8 = Charset.forName("UTF-8");

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        outputFolder = new OutputFolder(this);
        settings = new Settings(this);

        Source incoming = incomingSource(getIntent());
        // Straight-through case: something to convert arrived and the user already said which
        // app to open it in, so show a spinner rather than a UI nobody needs to touch.
        splashMode = incoming != null
                && settings.postAction() == Settings.PostAction.DIRECT
                && settings.directComponent() != null;

        pendingSource = incoming;

        if (splashMode) {
            setContentView(R.layout.activity_splash);
            splashStatus = findViewById(R.id.splash_status);
            splashBackend = findViewById(R.id.splash_backend);
            tintCompoundIcons(findViewById(R.id.splash_disclaimer));
            setDisclaimerFor(incoming);
            findViewById(R.id.splash_settings).setOnClickListener(v -> leaveSplash());
            // Before the surface is settled, name the backend only - the prompt is where
            // the surface is being chosen, so echoing it back there would be noise.
            showBackendInfo(false);
        } else {
            bindFullUi();
        }

        if (incoming == null) {
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
                                  ChoiceHandler handler) {
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
    }

    private int checkedIndex(RadioGroup options, String[] labels, int fallback) {
        int checked = options.getCheckedRadioButtonId();
        return checked >= 0 && checked < labels.length ? checked : fallback;
    }

    /** Shows exactly one of the splash's three faces. */
    private void setSplashFace(View wanted) {
        for (int id : new int[]{R.id.spinner_group, R.id.surface_group, R.id.summary_group}) {
            View face = findViewById(id);
            if (face != null) {
                face.setVisibility(face == wanted ? View.VISIBLE : View.GONE);
            }
        }
        // Both of these belong to the "about to route" state. The accuracy note sets
        // expectations before the track exists, and the backend line says what is coming -
        // but the summary already names the engine on its last line, so on that face they are
        // just noise. Handled here rather than at each call site so a reroute brings them back.
        boolean onSummary = wanted != null && wanted.getId() == R.id.summary_group;
        for (int id : new int[]{R.id.splash_disclaimer, R.id.splash_backend}) {
            View view = findViewById(id);
            if (view != null) {
                view.setVisibility(onSummary ? View.GONE : View.VISIBLE);
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
                    R.string.convert, true,
                    (index, openAfter) -> {
                        settings.setBrouterProfile(all[index]);
                        beginConversion(source, openAfter);
                    });
        } else {
            Settings.Surface[] all = Settings.Surface.values();
            showOptionPrompt(R.string.surface_title,
                    "Applies to cycling routes. Ignored for driving and walking.",
                    labelsOf(all), settings.surface().ordinal(),
                    R.string.convert, true,
                    (index, openAfter) -> {
                        settings.setSurface(all[index]);
                        beginConversion(source, openAfter);
                    });
        }
    }

    /** Google's own travel modes, which is what every router here is keyed on. */
    private static final String[] TRAVEL_MODES = {"cycling", "walking", "driving"};
    private static final String[] TRAVEL_MODE_LABELS =
            {"Cycling", "Walking or hiking", "Driving"};

    /**
     * Reroute. For a GPX file the travel mode comes first, because that is the setting most
     * likely to be wrong: GPX has no field for it, so the app may have had to guess, and a
     * hiking loop routed as a bike ride comes back twice as long. A Maps link states its own
     * mode, so there it goes straight to the engine.
     */
    private void promptForReroute(Source source) {
        if (source.isGpx()) {
            promptForTravelMode(source);
        } else {
            promptForEngine(source);
        }
    }

    private void promptForTravelMode(Source source) {
        // Whatever the last conversion settled on, so agreeing with it is one tap.
        String current = source.travelMode != null ? source.travelMode
                : (result != null ? result.travelMode : null);
        showOptionPrompt(R.string.travel_mode_title,
                "GPX files do not record this, so it may have been guessed. It decides which "
                        + "roads and paths the route may use.",
                TRAVEL_MODE_LABELS, indexOf(TRAVEL_MODES, current), R.string.next, false,
                (index, openAfter) -> {
                    source.travelMode = TRAVEL_MODES[index];
                    promptForEngine(source);
                });
    }

    private static int indexOf(String[] values, String wanted) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(wanted)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Engine, then that engine's own option, then convert again. Starting with the engine means
     * there is always something to change - OSRM takes no per-route option, so a profile-only
     * prompt would be a dead end there.
     */
    private void promptForEngine(Source source) {
        Settings.RoutingEngine[] all = Settings.RoutingEngine.values();
        showOptionPrompt(R.string.settings_engine,
                "Route the same stops again with a different backend.",
                labelsOf(all), settings.routingEngine().ordinal(),
                // Engines that take a further option chain to a second prompt, so this step
                // is "Next" rather than the thing that starts a conversion.
                needsPrompt(settings.routingEngine()) ? R.string.next : R.string.convert, false,
                (index, openAfter) -> {
                    Settings.RoutingEngine chosen = all[index];
                    settings.setRoutingEngine(chosen);
                    if (chosen == Settings.RoutingEngine.BROUTER
                            && !BRouterRouter.isInstalled(this)) {
                        appendLog("BRouter is not installed - this will fall back online.");
                    }
                    if (needsPrompt(chosen)) {
                        promptForChoice(source);
                    } else {
                        beginConversion(source, false);
                    }
                });
    }

    private String[] labelsOf(Object[] values) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Settings.BRouterProfile) {
                labels[i] = ((Settings.BRouterProfile) values[i]).label;
            } else if (values[i] instanceof Settings.Surface) {
                labels[i] = ((Settings.Surface) values[i]).label;
            } else {
                labels[i] = ((Settings.RoutingEngine) values[i]).label;
            }
        }
        return labels;
    }

    private void beginConversion(Source source, boolean openWhenDone) {
        forceOpen = openWhenDone;
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
        engineHint = findViewById(R.id.engine_hint);
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
        startConversion(incoming);
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
        if (splashMode) {
            showRouteSummary(converted);
        } else {
            shareButton.setEnabled(true);
            openButton.setEnabled(true);
        }
        exportAndShare();
    }

    /** Swaps the spinner for what the conversion actually produced. */
    private void showRouteSummary(Maps2Gpx.Result converted) {
        View summaryGroup = findViewById(R.id.summary_group);
        TextView summary = findViewById(R.id.splash_summary);
        Button reroute = findViewById(R.id.reroute_button);
        if (summaryGroup == null || summary == null) {
            return;
        }
        setSplashFace(summaryGroup);
        summary.setText(summaryOf(converted));
        showNotices(converted);

        TrackOutlineView outline = findViewById(R.id.track_outline);
        if (outline != null) {
            // Cleared rather than left alone when there is nothing to compare: the same view
            // is reused across conversions, so a stale grey line would be a lie.
            outline.setReferenceTrack(converted.sourceRoute == null
                    ? null : converted.sourceRoute.track);
            outline.setTrack(converted.route.track);
        }
        View legend = findViewById(R.id.track_legend);
        if (legend != null) {
            legend.setVisibility(converted.sourceRoute == null ? View.GONE : View.VISIBLE);
        }
        ElevationProfileView profile = findViewById(R.id.elevation_profile);
        if (profile != null) {
            profile.setTrack(converted.route.track);
            // No point reserving 76dp for an empty box when the DEM had nothing.
            profile.setVisibility(profile.hasProfile() ? View.VISIBLE : View.GONE);
        }

        Button open = findViewById(R.id.summary_open_button);
        if (open != null) {
            // Hidden while auto-open handles it; exportAndShare reveals it otherwise.
            open.setVisibility(View.GONE);
        }

        if (reroute != null) {
            Source source = pendingSource;
            boolean canReroute = source != null;
            reroute.setEnabled(canReroute);
            reroute.setOnClickListener(canReroute ? v -> promptForReroute(source) : null);
        }
    }

    /**
     * Puts the routing warnings on screen. Previously these only reached the log, where the
     * splash shows one line at a time and the whole trace scrolls past - so "your offline route
     * came from an online server" was invisible at exactly the moment it mattered.
     */
    private void showNotices(Maps2Gpx.Result converted) {
        TextView warning = findViewById(R.id.summary_warning);
        Button brouter = findViewById(R.id.brouter_button);
        List<String> messages = converted.notices.messages;

        if (warning != null) {
            warning.setVisibility(messages.isEmpty() ? View.GONE : View.VISIBLE);
            if (!messages.isEmpty()) {
                warning.setText(joinLines(messages));
                tintCompoundIcons(warning);
            }
        }
        if (brouter != null) {
            // Only offered when rd5 tiles were the actual reason: BRouter cannot fetch them by
            // itself and neither can we, so opening the app is the one useful next step.
            boolean missing = !converted.notices.missingSegments.isEmpty();
            brouter.setVisibility(missing ? View.VISIBLE : View.GONE);
            brouter.setOnClickListener(missing ? v -> openBRouter() : null);
        }
    }

    private static String joinLines(List<String> messages) {
        StringBuilder text = new StringBuilder();
        for (String message : messages) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(message);
        }
        return text.toString();
    }

    /**
     * Opens BRouter itself, where the rd5 segments are downloaded - Maps2Gpx cannot fetch them,
     * they are 5°x5° tiles managed inside that app. Falls back to its store listing, which is
     * the right destination if it turns out not to be installed after all.
     */
    private void openBRouter() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(BRouterRouter.PACKAGE);
        if (launch == null) {
            openBRouterListing();
            return;
        }
        try {
            startActivity(launch);
        } catch (RuntimeException e) {
            appendLog("Could not open BRouter: " + e.getMessage());
            toast("Could not open BRouter");
        }
    }

    private CharSequence summaryOf(Maps2Gpx.Result converted) {
        Route route = converted.route;
        StringBuilder text = new StringBuilder();
        text.append(converted.startLabel).append("  →  ").append(converted.endLabel);

        // Own estimate rather than the router's: BRouter reports no duration at all, and a
        // router's figure ignores how this rider actually rides. With BRouter the chosen
        // profile is a better speed hint than the link's travel mode.
        DurationEstimate estimate;
        if (settings.routingEngine() == Settings.RoutingEngine.BROUTER) {
            Settings.BRouterProfile profile = settings.brouterProfile();
            estimate = DurationEstimate.at(route.distanceMeters, route.ascentMeters(),
                    profile.kmh, profile.climbMetersPerHour);
        } else {
            estimate = DurationEstimate.of(route.distanceMeters, route.ascentMeters(),
                    converted.travelMode, settings.surface());
        }
        text.append(String.format(Locale.US, "\n%.1f km · %s",
                route.distanceMeters / 1000.0, formatDuration(estimate.seconds)));
        if (route.durationSeconds > 0) {
            text.append(" (router: ").append(formatDuration(route.durationSeconds)).append(')');
        }

        text.append(String.format(Locale.US, "\nEstimated at %.0f km/h", estimate.kmh));
        if (estimate.climbMetersPerHour > 0 && route.ascentMeters() > 0) {
            text.append(String.format(Locale.US, " + %.0f m/h climbing",
                    estimate.climbMetersPerHour));
        }

        if (route.hasElevation()) {
            text.append(String.format(Locale.US, "\nAscent %.0f m · Descent %.0f m",
                    route.ascentMeters(), route.descentMeters()));
            Double low = route.minEle();
            Double high = route.maxEle();
            if (low != null && high != null) {
                text.append(String.format(Locale.US, "\nElevation %.0f–%.0f m", low, high));
            }
        } else {
            text.append("\nNo elevation data");
        }

        if (converted.travelModeNote != null) {
            text.append("\nAs ").append(converted.travelMode)
                    .append(" (").append(converted.travelModeNote).append(')');
        }
        text.append(comparedWith(converted.sourceRoute, route));
        text.append(daylightAt(converted.endPoint));
        text.append("\n").append(route.profile);
        return text;
    }

    /**
     * What re-routing actually changed. The outline above shows where the two tracks diverge;
     * these totals say by how much, which together is the only way to tell "the same journey on
     * my roads" from "a different route entirely".
     */
    private static String comparedWith(Route source, Route route) {
        if (source == null || source.distanceMeters <= 0) {
            return "";
        }
        // Distance only, deliberately. Ascent looks like the obvious second number, but the two
        // figures come from different elevation sources - the file's own heights against this
        // app's DEM lookup - and on a flat 18 km re-route that difference alone accounted for
        // 57 m against 167 m. Comparing them would say more about the DEM than the route.
        double delta = route.distanceMeters - source.distanceMeters;
        return String.format(Locale.US, "\nOriginal %.1f km  (%+.1f km, %+.0f%%)",
                source.distanceMeters / 1000.0, delta / 1000.0,
                100 * delta / source.distanceMeters);
    }

    /** Sunrise and sunset at the destination, computed locally - no network needed. */
    private String daylightAt(LatLng destination) {
        if (destination == null) {
            return "";
        }
        long[] sun = SolarTimes.sunriseSunset(
                destination.lat, destination.lon, System.currentTimeMillis());
        if (sun == null) {
            return "\nThe sun does not rise or set there today";
        }
        return "\nSunrise " + clock(sun[0]) + " · Sunset " + clock(sun[1]);
    }

    /** Formatted in the device's own time zone, which is the one the user reads clocks in. */
    private static String clock(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(millis));
    }

    private static String formatDuration(double seconds) {
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " min";
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
            saved = outputFolder.save(tree, result.fileName, result.gpx);
        } catch (IOException | RuntimeException e) {
            leaveSplash();
            appendLog("Save failed: " + e.getMessage());
            appendLog("Pick a different folder with the Change button, or use Share.");
            toast("Save failed");
            return;
        }
        savedUri = saved.uri;
        savedName = saved.displayName;
        appendLog("Saved " + saved.displayName + " to " + outputFolder.describe(tree));
        if (!splashMode) {
            refreshSettingsUi();
        }

        if (!settings.autoOpen() && !forceOpen) {
            // Saving still happened; only the hand-off waits for the user.
            appendLog("Auto-open is off - use Open when you are ready.");
            revealSummaryOpenButton();
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
     * Makes opening an explicit act when auto-open is off. On the splash that is a button on
     * the summary; in the full UI the Open button is already there and always enabled.
     */
    private void revealSummaryOpenButton() {
        Button open = findViewById(R.id.summary_open_button);
        if (open == null) {
            return;
        }
        open.setVisibility(View.VISIBLE);
        open.setOnClickListener(v -> dispatchPostAction());
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
            appendLog("No folder picker available on this device: " + e.getMessage());
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

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (wasPending) {
                appendLog("No folder chosen - nothing saved. Use Share to send the GPX, "
                        + "or Folder to choose a destination.");
            }
            return;
        }
        Uri tree = data.getData();
        outputFolder.remember(tree, data);
        appendLog("Output folder: " + outputFolder.describe(tree));
        if (wasPending) {
            exportAndShare();
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
            return null;
        }
        try {
            return GpxFiles.cacheCopy(this, displayName(), result.gpx.getBytes(UTF8));
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

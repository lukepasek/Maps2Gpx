package pl.net.xtech.maps2gpx;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/** User configuration: what to do once a route has been converted. */
final class Settings {

    /** What happens automatically after a conversion finishes. */
    enum PostAction {
        /** ACTION_SEND chooser - messaging, mail, cloud storage. */
        SHARE,
        /** ACTION_VIEW chooser - GPX importers, asked every time. */
        VIEW,
        /** ACTION_VIEW straight into one remembered app, no chooser. */
        DIRECT
    }

    /** Which routing service reconstructs the track. */
    enum RoutingEngine {
        /** FOSSGIS OSRM, one instance per profile. Fast, but surface handling is fixed. */
        OSRM("OSRM (fixed profiles)"),
        /** FOSSGIS Valhalla. Slower, but accepts a surface preference for cycling. */
        VALHALLA("Valhalla (choose surface)"),
        /** The installed BRouter app. Fully offline, needs its segments downloaded. */
        BROUTER("BRouter (offline)");

        final String label;

        RoutingEngine(String label) {
            this.label = label;
        }
    }

    /** Travel type assumed when an imported GPX is routed again. */
    enum TravelMode {
        CYCLING("cycling", "Cycling"),
        WALKING("walking", "Walking or hiking"),
        DRIVING("driving", "Driving");

        final String value;
        final String label;

        TravelMode(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    /**
     * Surface presets for Valhalla cycling, mapped onto its {@code bicycle_type} and
     * {@code avoid_bad_surfaces} costing options. Verified to change the route where
     * unpaved alternatives exist.
     */
    enum Surface {
        PAVED("Paved only", "Road", 1.0),
        MOSTLY_PAVED("Mostly paved", "Hybrid", 0.7),
        GRAVEL("Gravel and unpaved", "Cross", 0.0),
        TRACKS("Tracks and trails", "Mountain", 0.0);

        final String label;
        final String bicycleType;
        final double avoidBadSurfaces;

        Surface(String label, String bicycleType, double avoidBadSurfaces) {
            this.label = label;
            this.bicycleType = bicycleType;
            this.avoidBadSurfaces = avoidBadSurfaces;
        }
    }

    /**
     * BRouter profiles, named so BRouter loads the matching {@code .brf} from its own
     * profiles2 directory. Naming a profile is the only thing that actually works: the
     * {@code v}/{@code fast} keys are resolved through BRouter's own config and were measured
     * to make no difference at all (fast=1 and fast=0 returned an identical route).
     *
     * <p>Every file here ships with BRouter, so none of them depend on the user having added
     * a custom profile. The speeds are for our own duration estimate, not BRouter's.
     */
    enum BRouterProfile {
        TREKKING("Trekking", "trekking", 15, 500),
        FASTBIKE("Fast bike", "fastbike", 20, 500),
        GRAVEL("Gravel", "gravel", 15, 500),
        MTB("MTB", "mtb", 12, 500),
        SHORTEST("Shortest", "shortest", 15, 500),
        HIKING("Hiking (mountain)", "hiking-mountain", 4.5, 600),
        CAR("Car", "car-vario", 50, 0);

        final String label;
        /** File name without the .brf suffix, which BRouter appends itself. */
        final String file;
        final double kmh;
        final double climbMetersPerHour;

        BRouterProfile(String label, String file, double kmh, double climbMetersPerHour) {
            this.label = label;
            this.file = file;
            this.kmh = kmh;
            this.climbMetersPerHour = climbMetersPerHour;
        }
    }

    static final String PREFS = "maps2gpx";
    private static final String KEY_ACTION = "post_action";
    private static final String KEY_DIRECT_COMPONENT = "direct_component";
    private static final String KEY_ENGINE = "routing_engine";
    private static final String KEY_GPX_TRAVEL_MODE = "gpx_travel_mode";
    private static final String KEY_SURFACE = "surface";
    private static final String KEY_AUTO_CLOSE = "auto_close";
    private static final String KEY_AUTO_OPEN = "auto_open";
    private static final String KEY_BROUTER_PROFILE = "brouter_profile";

    private final Context context;

    Settings(Context context) {
        this.context = context.getApplicationContext();
    }

    PostAction postAction() {
        String stored = prefs().getString(KEY_ACTION, PostAction.VIEW.name());
        try {
            return PostAction.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return PostAction.VIEW;
        }
    }

    void setPostAction(PostAction action) {
        prefs().edit().putString(KEY_ACTION, action.name()).apply();
    }

    /**
     * Whether the post-conversion action fires by itself. On by default, which is the
     * straight-through behaviour. Off means the route is still saved, but handing it to
     * another app waits for the Open button on the summary.
     */
    boolean autoOpen() {
        return prefs().getBoolean(KEY_AUTO_OPEN, true);
    }

    void setAutoOpen(boolean autoOpen) {
        prefs().edit().putBoolean(KEY_AUTO_OPEN, autoOpen).apply();
    }

    /**
     * Whether to close Maps2Gpx once the target app has been opened. On by default, which is
     * the straight-through behaviour; turning it off leaves the route summary on screen.
     * Only meaningful when {@link #autoOpen()} is on or Open is pressed.
     */
    boolean autoCloseAfterOpen() {
        return prefs().getBoolean(KEY_AUTO_CLOSE, true);
    }

    void setAutoCloseAfterOpen(boolean autoClose) {
        prefs().edit().putBoolean(KEY_AUTO_CLOSE, autoClose).apply();
    }

    RoutingEngine routingEngine() {
        String stored = prefs().getString(KEY_ENGINE, RoutingEngine.OSRM.name());
        try {
            return RoutingEngine.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return RoutingEngine.OSRM;
        }
    }

    void setRoutingEngine(RoutingEngine engine) {
        prefs().edit().putString(KEY_ENGINE, engine.name()).apply();
    }

    TravelMode gpxTravelMode() {
        String stored = prefs().getString(KEY_GPX_TRAVEL_MODE, TravelMode.CYCLING.name());
        try {
            return TravelMode.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return TravelMode.CYCLING;
        }
    }

    void setGpxTravelMode(TravelMode travelMode) {
        prefs().edit().putString(KEY_GPX_TRAVEL_MODE, travelMode.name()).apply();
    }

    BRouterProfile brouterProfile() {
        String stored = prefs().getString(KEY_BROUTER_PROFILE, BRouterProfile.TREKKING.name());
        try {
            return BRouterProfile.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return BRouterProfile.TREKKING;
        }
    }

    void setBrouterProfile(BRouterProfile profile) {
        prefs().edit().putString(KEY_BROUTER_PROFILE, profile.name()).apply();
    }

    /** Last surface choice - the splash prompt starts here, and other paths just use it. */
    Surface surface() {
        String stored = prefs().getString(KEY_SURFACE, Surface.GRAVEL.name());
        try {
            return Surface.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return Surface.GRAVEL;
        }
    }

    void setSurface(Surface surface) {
        prefs().edit().putString(KEY_SURFACE, surface.name()).apply();
    }

    /** The app remembered for {@link PostAction#DIRECT}, or null if not chosen yet. */
    ComponentName directComponent() {
        String stored = prefs().getString(KEY_DIRECT_COMPONENT, null);
        return stored == null ? null : ComponentName.unflattenFromString(stored);
    }

    void setDirectComponent(ComponentName component) {
        SharedPreferences.Editor edit = prefs().edit();
        if (component == null) {
            edit.remove(KEY_DIRECT_COMPONENT);
        } else {
            edit.putString(KEY_DIRECT_COMPONENT, component.flattenToString());
        }
        edit.apply();
    }

    /**
     * Label for the remembered app. Package visibility rules mean we usually cannot load
     * the real application label, so the package name is the honest fallback.
     */
    String describeDirectComponent() {
        ComponentName component = directComponent();
        if (component == null) {
            return null;
        }
        try {
            return context.getPackageManager()
                    .getActivityInfo(component, 0)
                    .loadLabel(context.getPackageManager())
                    .toString();
        } catch (Exception e) {
            return component.getPackageName();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

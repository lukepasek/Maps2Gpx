package pl.net.xtech.maps2gpx;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** App information, open-source acknowledgements, and external service terms. */
public class AboutActivity extends Activity {
    private static final String APACHE_2 = "https://www.apache.org/licenses/LICENSE-2.0";
    private static final String BSD_2 = "https://opensource.org/license/bsd-2-clause";
    private static final String MIT = "https://opensource.org/license/mit";
    private static final String GPL_3 = "https://www.gnu.org/licenses/gpl-3.0.html";
    private static final String ODBL_1 = "https://opendatacommons.org/licenses/odbl/1-0/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        findViewById(R.id.about_back).setOnClickListener(view -> finish());
        ((TextView) findViewById(R.id.about_version)).setText(
                getString(R.string.about_version, BuildConfig.VERSION_NAME));

        LinearLayout content = findViewById(R.id.about_entries);
        addSection(content, R.string.about_open_source);
        addEntry(content, "AndroidX libraries",
                "Core, Annotation, Activity, Fragment, Lifecycle, Saved State, Collection, "
                        + "Interpolator, Startup, Profile Installer and supporting modules.",
                "Apache License 2.0", APACHE_2,
                "https://developer.android.com/jetpack/androidx");
        addEntry(content, "MapLibre Native for Android 11.13.5",
                "Map rendering, gestures, GeoJSON and Turf modules.",
                "BSD 2-Clause License", BSD_2,
                "https://github.com/maplibre/maplibre-native");
        addEntry(content, "Kotlin standard library and coroutines",
                "Runtime support used by bundled libraries.",
                "Apache License 2.0", APACHE_2,
                "https://github.com/JetBrains/kotlin");
        addEntry(content, "OkHttp and Okio",
                "HTTP and I/O runtime used by MapLibre.",
                "Apache License 2.0", APACHE_2,
                "https://square.github.io/okhttp/");
        addEntry(content, "Timber",
                "Logging runtime used by MapLibre.",
                "Apache License 2.0", APACHE_2,
                "https://github.com/JakeWharton/timber");
        addEntry(content, "JUnit 4.13.2",
                "Development and unit-testing framework; not shipped as app runtime code.",
                "Eclipse Public License 1.0", "https://www.eclipse.org/legal/epl-v10.html",
                "https://junit.org/junit4/");

        addSection(content, R.string.about_data_services);
        addEntry(content, "OpenStreetMap",
                "Road, path and surface data from OpenStreetMap contributors.",
                "Open Data Commons ODbL 1.0", ODBL_1,
                "https://www.openstreetmap.org/copyright");
        addEntry(content, "OSRM",
                "Routing through FOSSGIS and the Project OSRM demo service, using OSM data.",
                "BSD 2-Clause License", BSD_2,
                "https://project-osrm.org/");
        addEntry(content, "Valhalla",
                "Routing, elevation and surface analysis through the FOSSGIS public service, "
                        + "using OSM data.",
                "MIT License", MIT,
                "https://github.com/valhalla/valhalla");
        addEntry(content, "Nominatim",
                "Place search and reverse geocoding through the OpenStreetMap public service.",
                "GNU GPL 3.0", GPL_3,
                "https://nominatim.org/");
        addEntry(content, "BRouter",
                "Optional offline routing through the separately installed BRouter app.",
                "MIT License", MIT,
                "https://github.com/abrensch/brouter");
        addEntry(content, "Mapbox Outdoors",
                "Online basemap style and tiles. This is a proprietary service; map data "
                        + "includes OpenStreetMap under ODbL 1.0.",
                "Mapbox Terms of Service", "https://www.mapbox.com/legal/tos/",
                "https://www.mapbox.com/about/maps/");
    }

    private void addSection(LinearLayout content, int titleResource) {
        TextView heading = new TextView(this);
        heading.setText(titleResource);
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(20), 0, dp(6));
        content.addView(heading, matchWrap());
    }

    private void addEntry(LinearLayout content, String title, String description,
                          String license, String licenseUrl, String projectUrl) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int titleStart = text.length();
        text.append(title);
        text.setSpan(new StyleSpan(Typeface.BOLD), titleStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new URLSpan(projectUrl), titleStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append('\n').append(description).append('\n');
        int licenseStart = text.length();
        text.append(license);
        text.setSpan(new URLSpan(licenseUrl), licenseStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView entry = new TextView(this);
        entry.setText(text);
        entry.setTextSize(14);
        entry.setLineSpacing(0, 1.12f);
        entry.setMovementMethod(LinkMovementMethod.getInstance());
        entry.setPadding(0, dp(8), 0, dp(12));
        content.addView(entry, matchWrap());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
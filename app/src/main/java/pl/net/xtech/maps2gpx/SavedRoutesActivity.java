package pl.net.xtech.maps2gpx;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The home screen: every GPX in the chosen output folder, newest first, and a way into settings.
 *
 * <p>The folder is the record - there is no separate database of past conversions, because there
 * does not need to be one: the files are already there, they are already named after their
 * endpoints, and a list built from a private index would drift out of step with what is
 * actually on disk the first time the user moved or deleted anything.
 *
 * <p>Deliberately holds no conversion logic. {@link MainActivity} owns incoming Maps shares,
 * routing settings and conversion; this activity is the on-device GPX library.
 */
public class SavedRoutesActivity extends Activity {

    private static final String TAG = "Maps2Gpx";
    /** The timestamp this app appends to its own file names, e.g. {@code _20260818-1402.gpx}. */
    private static final Pattern OWN_STAMP =
            Pattern.compile("_\\d{8}-\\d{4}\\.gpx$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_EXTENSION =
            Pattern.compile("\\.gpx$", Pattern.CASE_INSENSITIVE);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OutputFolder outputFolder;
    private Settings settings;

    private final List<OutputFolder.Entry> allEntries = new ArrayList<>();
    private final Map<String, Double> distanceCache = new HashMap<>();
    private ListView list;
    private EditText search;
    private Drawable clearSearchIcon;
    private TextView message;
    private ProgressBar progress;
    private SavedAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_saved);

        outputFolder = new OutputFolder(this);
        settings = new Settings(this);

        list = findViewById(R.id.saved_list);
        search = findViewById(R.id.saved_search);
        message = findViewById(R.id.saved_message);
        progress = findViewById(R.id.saved_progress);

        clearSearchIcon = getDrawable(R.drawable.ic_clear_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                updateClearSearchIcon(text.length() > 0);
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
        search.setOnTouchListener((view, event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP
                    || search.getCompoundDrawablesRelative()[2] == null) {
                return false;
            }
            int clearStart = search.getWidth() - search.getPaddingEnd()
                    - clearSearchIcon.getIntrinsicWidth();
            if (event.getX() < clearStart) {
                return false;
            }
            search.setText("");
            return true;
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            dismissKeyboard();
            return true;
        });
        findViewById(R.id.saved_menu).setOnClickListener(this::showMenu);

        adapter = new SavedAdapter(this, new ArrayList<OutputFolder.Entry>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            OutputFolder.Entry entry = adapter.getItem(position);
            if (entry != null) {
                startActivity(SavedRouteDetailActivity.intentFor(this, entry));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read every time: a conversion may have written a file while this screen was in the
        // background, and the folder is shared storage that anything can change.
        reload();
    }

    private void reload() {
        Uri tree = outputFolder.treeUri();
        if (tree == null) {
            allEntries.clear();
            adapter.clear();
            showMessage(getString(R.string.saved_no_folder));
            return;
        }
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                List<OutputFolder.Entry> entries = outputFolder.list(tree);
                mainHandler.post(() -> show(entries));
                loadDistances(entries);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not list " + tree, e);
                mainHandler.post(() -> {
                    progress.setVisibility(View.INVISIBLE);
                    allEntries.clear();
                    adapter.clear();
                    showMessage(getString(R.string.saved_failed, e.getMessage()));
                });
            }
        });
    }

    private void show(List<OutputFolder.Entry> entries) {
        progress.setVisibility(View.INVISIBLE);
        allEntries.clear();
        allEntries.addAll(entries);
        if (entries.isEmpty()) {
            adapter.clear();
            showMessage(getString(R.string.saved_empty));
        } else {
            applyFilter();
        }
    }

    private void loadDistances(List<OutputFolder.Entry> entries) {
        for (OutputFolder.Entry entry : entries) {
            String cacheKey = entry.uri + ":" + entry.sizeBytes + ":" + entry.modifiedMillis;
            Double cached = distanceCache.get(cacheKey);
            if (cached != null) {
                entry.distanceMeters = cached;
                continue;
            }
            try (InputStream input = getContentResolver().openInputStream(entry.uri)) {
                if (input == null) {
                    continue;
                }
                entry.distanceMeters = GpxReader.read(input).original.distanceMeters;
                distanceCache.put(cacheKey, entry.distanceMeters);
                mainHandler.post(() -> {
                    if (!isFinishing() && allEntries.contains(entry)) {
                        adapter.notifyDataSetChanged();
                    }
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not measure " + entry.displayName, e);
            }
        }
    }

    private void applyFilter() {
        String query = searchKey(search.getText().toString());
        adapter.clear();
        for (OutputFolder.Entry entry : allEntries) {
            String name = searchKey(readableName(entry.displayName));
            if (query.isEmpty() || name.contains(query)) {
                adapter.add(entry);
            }
        }
        if (adapter.isEmpty() && !allEntries.isEmpty()) {
            showMessage(getString(R.string.saved_no_matches));
        } else if (!adapter.isEmpty()) {
            message.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
        }
    }

    static String searchKey(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        String normalized = Normalizer.normalize(value.substring(start, end), Normalizer.Form.NFD)
                .toLowerCase(Locale.ROOT)
                .replace('ł', 'l');
        StringBuilder key = new StringBuilder(normalized.length());
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                key.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return key.toString();
    }

    private void updateClearSearchIcon(boolean visible) {
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, visible ? clearSearchIcon : null, null);
    }

    private void dismissKeyboard() {
        search.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.inflate(R.menu.saved_routes_menu);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_settings) {
                startActivity(new Intent(this, MainActivity.class));
                return true;
            }
            if (item.getItemId() == R.id.menu_about) {
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showMessage(String text) {
        progress.setVisibility(View.INVISIBLE);
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
    }

    /**
     * Opens the file in a GPX app. Honours "open directly in one app" when that is configured,
     * because that is the whole point of the setting - but falls back to the chooser rather than
     * clearing it if the remembered app has gone, since browsing old files is not the place to
     * reconfigure the app.
     */
    private void open(OutputFolder.Entry entry) {
        withCacheCopy(entry, uri -> {
            ComponentName target = settings.postAction() == Settings.PostAction.DIRECT
                    ? settings.directComponent() : null;
            Intent view = GpxFiles.viewIntent(uri);
            if (target != null) {
                view.setComponent(target);
            }
            try {
                // Without an explicit target Android uses the system default, or asks once when
                // no default exists. The library does not change the app's remembered setting.
                startActivity(view);
            } catch (ActivityNotFoundException e) {
                if (target == null) {
                    throw e;
                }
                toast(target.getPackageName() + " can no longer open this");
                startActivity(GpxFiles.viewIntent(uri));
            }
        });
    }

    private interface UriAction {
        void run(Uri uri);
    }

    /**
     * Reads the file and hands over a FileProvider copy rather than the SAF document URI, for the
     * reasons in {@link GpxFiles#cacheCopy} - the same hand-off a fresh conversion uses. Reading
     * is shared storage I/O, so it happens off the main thread.
     */
    private void withCacheCopy(OutputFolder.Entry entry, UriAction action) {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                byte[] gpx = GpxFiles.readAll(this, entry.uri);
                Uri shareable = GpxFiles.cacheCopy(this, entry.displayName, gpx);
                mainHandler.post(() -> {
                    progress.setVisibility(View.INVISIBLE);
                    if (isFinishing()) {
                        return;
                    }
                    try {
                        action.run(shareable);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Could not hand over " + entry.displayName, e);
                        toast("Could not open that file");
                    }
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not read " + entry.uri, e);
                mainHandler.post(() -> {
                    progress.setVisibility(View.INVISIBLE);
                    toast("Could not read " + entry.displayName);
                });
            }
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /** File name, date and size, plus an external-open button that preserves the row's tap. */
    private final class SavedAdapter extends ArrayAdapter<OutputFolder.Entry> {

        SavedAdapter(Context context, List<OutputFolder.Entry> entries) {
            super(context, R.layout.saved_row, R.id.saved_name, entries);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = super.getView(position, convertView, parent);
            OutputFolder.Entry entry = getItem(position);
            if (entry == null) {
                return row;
            }
            ((TextView) row.findViewById(R.id.saved_name)).setText(readableName(entry.displayName));
            ((TextView) row.findViewById(R.id.saved_meta)).setText(describe(entry));
            ImageButton openButton = row.findViewById(R.id.saved_share);
            openButton.setOnClickListener(v -> open(entry));
            return row;
        }
    }

    /**
     * The file name made readable: underscores back to spaces, and this app's own trailing
     * timestamp dropped because the row already shows the file's date properly. Names that do
     * not carry that stamp - anything not written here - only lose the extension.
     */
    static String readableName(String fileName) {
        String out = OWN_STAMP.matcher(fileName).replaceFirst("");
        if (out.equals(fileName)) {
            out = ANY_EXTENSION.matcher(fileName).replaceFirst("");
        }
        return out.replace('_', ' ');
    }

    private static String describe(OutputFolder.Entry entry) {
        StringBuilder text = new StringBuilder();
        if (entry.modifiedMillis > 0) {
            text.append(new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                    .format(new Date(entry.modifiedMillis)));
        }
        if (entry.sizeBytes > 0) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append(readableSize(entry.sizeBytes));
        }
        if (entry.distanceMeters >= 0) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append(String.format(Locale.getDefault(), "%.1f km",
                    entry.distanceMeters / 1000.0));
        }
        return text.toString();
    }

    static String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}

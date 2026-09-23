package pl.net.xtech.maps2gpx;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The home screen: a link to convert, every GPX already sitting in the chosen output folder
 * newest first, and a way into the settings.
 *
 * <p>The folder is the record - there is no separate database of past conversions, because there
 * does not need to be one: the files are already there, they are already named after their
 * endpoints, and a list built from a private index would drift out of step with what is
 * actually on disk the first time the user moved or deleted anything.
 *
 * <p>Deliberately holds no conversion logic. {@link MainActivity} owns that, along with the
 * progress log, the summary and every intent filter other apps see; pasting a link here just
 * hands the text to it exactly as a share from Google Maps would.
 */
public class SavedRoutesActivity extends Activity {

    private static final String TAG = "Maps2Gpx";
    private static final int REQUEST_PICK_GPX = 1;

    /** The timestamp this app appends to its own file names, e.g. {@code _20260818-1402.gpx}. */
    private static final Pattern OWN_STAMP =
            Pattern.compile("_\\d{8}-\\d{4}\\.gpx$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_EXTENSION =
            Pattern.compile("\\.gpx$", Pattern.CASE_INSENSITIVE);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OutputFolder outputFolder;
    private Settings settings;

    private ListView list;
    private TextView folderLine;
    private TextView message;
    private ProgressBar progress;
    private EditText linkInput;
    private View hint;
    private SavedAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved);

        outputFolder = new OutputFolder(this);
        settings = new Settings(this);

        list = findViewById(R.id.saved_list);
        folderLine = findViewById(R.id.saved_folder);
        message = findViewById(R.id.saved_message);
        progress = findViewById(R.id.saved_progress);
        linkInput = findViewById(R.id.link_input);
        hint = findViewById(R.id.saved_hint);

        findViewById(R.id.convert_button).setOnClickListener(v -> convert());
        findViewById(R.id.reroute_gpx_button).setOnClickListener(v -> pickGpxFile());
        findViewById(R.id.settings_button).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));

        adapter = new SavedAdapter(this, new ArrayList<OutputFolder.Entry>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            OutputFolder.Entry entry = adapter.getItem(position);
            if (entry != null) {
                open(entry);
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

    /**
     * Hands the pasted text to {@link MainActivity} as a share, which is the same door Google
     * Maps comes through - so the splash, the engine prompts, the summary and the log all behave
     * identically however the link arrived. Nothing about converting is duplicated here.
     */
    private void convert() {
        String text = linkInput.getText().toString().trim();
        if (text.isEmpty()) {
            toast("Paste a Google Maps link first.");
            return;
        }
        startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text));
    }

    /** Lets the user hand over a GPX without going through another app's share sheet. */
    private void pickGpxFile() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        // Android has no MIME entry for .gpx, so a provider may report anything at all -
        // */* with the extra below is what actually lists the files.
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                GpxFiles.MIME, "application/xml", "text/xml", "application/octet-stream"});
        try {
            startActivityForResult(Intent.createChooser(pick, getString(R.string.pick_gpx)),
                    REQUEST_PICK_GPX);
        } catch (RuntimeException e) {
            toast("No file picker available on this device");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_GPX || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        // Handed on as a VIEW of the document, which is the same door an "open with" from a file
        // manager comes through. The read grant belongs to the package rather than to this
        // activity, so MainActivity can open it without any further ceremony.
        startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(data.getData(), GpxFiles.MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private void reload() {
        Uri tree = outputFolder.treeUri();
        if (tree == null) {
            adapter.clear();
            folderLine.setText(R.string.folder_none);
            showMessage(getString(R.string.saved_no_folder));
            return;
        }
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String where = outputFolder.describe(tree);
                List<OutputFolder.Entry> entries = outputFolder.list(tree);
                mainHandler.post(() -> show(where, entries));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not list " + tree, e);
                mainHandler.post(() -> {
                    progress.setVisibility(View.INVISIBLE);
                    adapter.clear();
                    folderLine.setText(R.string.folder_none);
                    showMessage(getString(R.string.saved_failed, e.getMessage()));
                });
            }
        });
    }

    private void show(String folderName, List<OutputFolder.Entry> entries) {
        progress.setVisibility(View.INVISIBLE);
        folderLine.setText(getString(R.string.saved_folder_line, folderName, entries.size()));
        adapter.clear();
        adapter.addAll(entries);
        if (entries.isEmpty()) {
            showMessage(getString(R.string.saved_empty));
        } else {
            message.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            hint.setVisibility(View.VISIBLE);
        }
    }

    private void showMessage(String text) {
        progress.setVisibility(View.INVISIBLE);
        hint.setVisibility(View.GONE);
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
            if (target != null) {
                Intent view = GpxFiles.viewIntent(uri);
                view.setComponent(target);
                try {
                    startActivity(view);
                    return;
                } catch (ActivityNotFoundException e) {
                    toast(target.getPackageName() + " can no longer open this");
                }
            }
            // No chosen-app callback on purpose: unlike the post-conversion hand-off, picking an
            // app here must not silently become the remembered "open directly" target.
            startActivity(GpxFiles.openChooser(this, uri, entry.displayName, null));
        });
    }

    private void share(OutputFolder.Entry entry) {
        withCacheCopy(entry, uri ->
                startActivity(GpxFiles.shareChooser(this, uri, entry.displayName)));
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

    /** File name, date and size, plus a Share button that does not swallow the row's own tap. */
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
            Button shareButton = row.findViewById(R.id.saved_share);
            shareButton.setOnClickListener(v -> share(entry));
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

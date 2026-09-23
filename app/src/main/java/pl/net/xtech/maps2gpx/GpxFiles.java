package pl.net.xtech.maps2gpx;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The boundary between a {@code .gpx} file and the rest of the device: reading one in, and
 * handing one out.
 *
 * <p>Extracted so the route summary and the saved-files screen hand a file over in exactly the
 * same way. Every rule in here was learned from a real failure, and keeping two copies of them
 * would mean fixing the next one twice - or, worse, fixing it in only one place.
 */
final class GpxFiles {

    private static final String TAG = "Maps2Gpx";

    /**
     * Android's MIME map has no {@code .gpx} entry, so this has to be stated everywhere rather
     * than inferred: on the intent, and on its ClipData, or a receiver that asks the provider
     * for the stream's type gets {@code application/octet-stream}.
     */
    static final String MIME = "application/gpx+xml";

    /** Far past any real route, and small enough that the bytes are safe to hold in memory. */
    static final int MAX_BYTES = 32 * 1024 * 1024;

    private GpxFiles() {
    }

    static byte[] readAll(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Nothing could be opened at " + uri);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (out.size() + read > MAX_BYTES) {
                    throw new IOException("That file is bigger than "
                            + (MAX_BYTES / (1024 * 1024)) + " MB - it is not a route.");
                }
                out.write(buffer, 0, read);
            }
            if (out.size() == 0) {
                throw new IOException("That file is empty.");
            }
            return out.toByteArray();
        } catch (SecurityException e) {
            throw new IOException("No permission to read that file: " + e.getMessage(), e);
        }
    }

    /** The provider's own name for the file, falling back to the last path segment. */
    static String displayNameOf(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read a display name for " + uri, e);
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "the GPX file" : segment;
    }

    /**
     * A FileProvider URI for the bytes, deliberately <em>not</em> the SAF document URI of a
     * saved file: a SAF document URI ends in the document id
     * ({@code …/document/primary%3AFolder%2Fname.gpx}), so receiving apps that build a temp path
     * from the last path segment try to write into a non-existent "primary:Folder" directory and
     * fail with ENOENT - and its path does not look like a plain {@code *.gpx} to a path-glob
     * intent filter. Any permanent copy stays where it is; this is only the hand-off.
     */
    static Uri cacheCopy(Context context, String displayName, byte[] gpx) throws IOException {
        File dir = new File(context.getCacheDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        deleteStaleCopies(dir, displayName);
        File file = new File(dir, displayName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(gpx);
        }
        return FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", file);
    }

    /** Keeps the share cache from growing without bound. */
    private static void deleteStaleCopies(File dir, String keep) {
        File[] existing = dir.listFiles();
        if (existing == null) {
            return;
        }
        for (File f : existing) {
            if (!f.getName().equals(keep) && !f.delete()) {
                Log.w(TAG, "Could not delete stale share copy " + f);
            }
        }
    }

    /**
     * ACTION_VIEW, which is how GPX importers actually advertise themselves - they typically
     * register for VIEW on a {@code .gpx} URI and have no SEND filter at all, so a share sheet
     * never reaches them.
     */
    static Intent viewIntent(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, MIME);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return view;
    }

    /** ACTION_SEND, for messaging, mail and cloud storage. */
    static Intent sendIntent(Uri uri, String displayName) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(MIME);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, displayName);
        send.putExtra(Intent.EXTRA_TITLE, displayName);
        // Declaring the type on the ClipData too makes it explicit either way, and carries the
        // URI grant.
        send.setClipData(new ClipData(
                displayName, new String[]{MIME}, new ClipData.Item(uri)));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }

    /**
     * Chooser for opening the file elsewhere. The SEND intent rides along as an alternate so
     * messaging apps still appear where the platform chooser supports that.
     *
     * @param chosen where to report the user's pick, so "open directly" has something to
     *               remember. Null below API 22, where the chooser cannot report it.
     */
    static Intent openChooser(Context context, Uri uri, String displayName, IntentSender chosen) {
        Intent chooser = Intent.createChooser(viewIntent(uri), "Open " + displayName, chosen);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            chooser.putExtra(Intent.EXTRA_ALTERNATE_INTENTS,
                    new Intent[]{sendIntent(uri, displayName)});
        }
        return excludeOurselves(context, chooser);
    }

    static Intent shareChooser(Context context, Uri uri, String displayName) {
        return excludeOurselves(context,
                Intent.createChooser(sendIntent(uri, displayName), "Share " + displayName));
    }

    /**
     * Maps2Gpx answers both VIEW and SEND on a {@code .gpx} now, for re-routing - so without this
     * it offers itself in its own chooser. Handing the file back to the app it came from is not a
     * choice worth showing, and re-routing already has its own two entry points.
     */
    private static Intent excludeOurselves(Context context, Intent chooser) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                    new ComponentName[]{new ComponentName(context, MainActivity.class)});
        }
        return chooser;
    }

    /**
     * A representative URI for capability queries. The file need not exist - matching is on
     * scheme, authority and the .gpx path, which is exactly what GPX importers filter on.
     */
    static Uri probeUri(Context context) {
        File file = new File(new File(context.getCacheDir(), "shared"), "route.gpx");
        return FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", file);
    }
}

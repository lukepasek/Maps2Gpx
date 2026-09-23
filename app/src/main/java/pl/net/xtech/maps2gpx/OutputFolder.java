package pl.net.xtech.maps2gpx;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The shared-storage folder the user picked once, remembered across runs via a
 * persisted SAF tree permission. No storage permission is needed - the grant travels
 * with the tree URI.
 */
final class OutputFolder {

    private static final String TAG = "Maps2Gpx";
    private static final String PREFS = "maps2gpx";
    private static final String KEY_TREE = "output_tree_uri";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String GPX_MIME = "application/gpx+xml";

    private final Context context;

    OutputFolder(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Intent that asks the user to choose the destination folder. */
    static Intent pickIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    /** Remembers the picked tree and holds onto the permission across reboots. */
    void remember(Uri treeUri, Intent result) {
        int flags = result.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            context.getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (SecurityException e) {
            Log.w(TAG, "Could not persist permission for " + treeUri, e);
        }
        prefs().edit().putString(KEY_TREE, treeUri.toString()).apply();
    }

    /** @return the remembered folder, or null if none is usable yet. */
    Uri treeUri() {
        String stored = prefs().getString(KEY_TREE, null);
        if (stored == null) {
            return null;
        }
        Uri tree = Uri.parse(stored);
        // A grant can be revoked (folder deleted, SD card pulled, app data cleared on
        // the provider side), so confirm it before promising the user a save.
        for (android.content.UriPermission held
                : context.getContentResolver().getPersistedUriPermissions()) {
            if (held.getUri().equals(tree) && held.isWritePermission()) {
                return tree;
            }
        }
        return null;
    }

    /** Human-readable name of the chosen folder, for the log. */
    String describe(Uri treeUri) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            String name = queryDisplayName(doc);
            if (name != null) {
                return name;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not describe " + treeUri, e);
        }
        return treeUri.getLastPathSegment() != null
                ? treeUri.getLastPathSegment() : treeUri.toString();
    }

    /**
     * Writes the GPX into the chosen folder.
     *
     * @return where it landed - the document URI plus the name the provider actually used.
     */
    Saved save(Uri treeUri, String fileName, String gpx) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));

        Uri file;
        try {
            file = DocumentsContract.createDocument(resolver, parent, GPX_MIME, fileName);
        } catch (Exception e) {
            throw new IOException("Could not create " + fileName + " in the chosen folder: "
                    + e.getMessage(), e);
        }
        if (file == null) {
            throw new IOException("The chosen folder refused to create " + fileName);
        }

        try (OutputStream out = resolver.openOutputStream(file)) {
            if (out == null) {
                throw new IOException("Could not open " + file + " for writing");
            }
            out.write(gpx.getBytes(UTF8));
        }

        String actualName = queryDisplayName(file);
        return new Saved(file, actualName != null ? actualName : fileName);
    }

    /**
     * The GPX files already in the chosen folder, newest first.
     *
     * <p>Uses the tree grant rather than any storage permission, so this only ever sees the one
     * folder the user pointed at. Anything that is not a {@code .gpx} is skipped: the folder is
     * the user's own and may well hold other things.
     *
     * <p>Matched on the file name rather than the reported MIME type, because Android has no
     * {@code .gpx} entry in its MIME map - providers report these as
     * {@code application/octet-stream} as often as not, and filtering on that would either drop
     * every file or admit every file.
     */
    List<Entry> list(Uri treeUri) throws IOException {
        Uri children;
        try {
            children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        } catch (RuntimeException e) {
            throw new IOException("That folder is no longer readable: " + e.getMessage(), e);
        }

        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        List<Entry> found = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(children, columns, null, null, null)) {
            if (c == null) {
                throw new IOException("The folder returned nothing at all - the grant may have "
                        + "been revoked.");
            }
            while (c.moveToNext()) {
                String name = c.isNull(1) ? null : c.getString(1);
                if (name == null || !name.toLowerCase(Locale.US).endsWith(".gpx")) {
                    continue;
                }
                found.add(new Entry(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                        name,
                        c.isNull(2) ? 0 : c.getLong(2),
                        c.isNull(3) ? 0 : c.getLong(3)));
            }
        } catch (SecurityException e) {
            throw new IOException("No longer allowed to read that folder: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("Could not list that folder: " + e.getMessage(), e);
        }

        // Newest first: the file someone wants is almost always the one just written.
        Collections.sort(found, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byTime = Long.compare(b.modifiedMillis, a.modifiedMillis);
                // Providers may report no timestamp at all; fall back to the name, which for
                // this app's own files ends in one anyway.
                return byTime != 0 ? byTime : b.displayName.compareTo(a.displayName);
            }
        });
        return found;
    }

    /** One GPX file sitting in the chosen folder. */
    static final class Entry {
        final Uri uri;
        final String displayName;
        final long sizeBytes;
        /** 0 when the provider does not report one. */
        final long modifiedMillis;

        Entry(Uri uri, String displayName, long sizeBytes, long modifiedMillis) {
            this.uri = uri;
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
            this.modifiedMillis = modifiedMillis;
        }
    }

    private String queryDisplayName(Uri documentUri) {
        String[] columns = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = context.getContentResolver()
                .query(documentUri, columns, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read display name of " + documentUri, e);
        }
        return null;
    }

    static final class Saved {
        final Uri uri;
        final String displayName;

        Saved(Uri uri, String displayName) {
            this.uri = uri;
            this.displayName = displayName;
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

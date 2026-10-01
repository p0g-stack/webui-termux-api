package com.termux.api.apis;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.JsonWriter;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opens Android's document picker and copies what the user picks into a folder (webui fork).
 *
 * Extras: --es dir <folder this app can write> (required), --esa mime <types> (default any),
 * --ez multiple true to allow several documents.
 *
 * Answers, after the picker closes, a JSON array with one object per copied document:
 * name, mime, size and path. An empty array when the user cancels. Unlike StorageGet it
 * answers only once the copies are complete, so the caller can use them at once.
 */
public class DocumentOpenAPI {

    private static final String LOG_TAG = "DocumentOpenAPI";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        final String dir = intent.getStringExtra("dir");
        if (dir == null || dir.isEmpty()) {
            ResultReturner.returnData(apiReceiver, intent, out -> out.println("ERROR: dir not passed"));
            return;
        }
        Intent startIntent = new Intent(context, DocumentOpenActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("dir", dir)
                .putExtra("mime", intent.getStringArrayExtra("mime"))
                .putExtra("multiple", intent.getBooleanExtra("multiple", false));
        ResultReturner.copyIntentExtras(intent, startIntent);
        context.startActivity(startIntent);
    }

    /** One copied document. */
    static final class Picked {
        String name;
        String mime;
        long size;
        String path;
    }

    public static class DocumentOpenActivity extends Activity {

        private static final String LOG_TAG = "DocumentOpenActivity";

        private boolean started;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            started = savedInstanceState != null && savedInstanceState.getBoolean("started");
        }

        @Override
        protected void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean("started", started);
        }

        @Override
        protected void onResume() {
            super.onResume();
            if (started) return;
            started = true;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            String[] mime = getIntent().getStringArrayExtra("mime");
            if (mime != null && mime.length == 1) {
                pick.setType(mime[0]);
            } else {
                pick.setType("*/*");
                if (mime != null && mime.length > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, mime);
            }
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, getIntent().getBooleanExtra("multiple", false));
            startActivityForResult(pick, 1);
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            final List<Uri> uris = new ArrayList<>();
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
            final File dir = new File(getIntent().getStringExtra("dir"));
            final Intent answerIntent = getIntent();
            // Copy while this activity is alive: the read grant for the picked documents
            // belongs to it. Answer, then finish.
            new Thread(() -> {
                List<Picked> picked;
                String error = null;
                try {
                    picked = copyAll(this, uris, dir);
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Copying picked documents failed", e);
                    picked = new ArrayList<>();
                    error = e.getMessage();
                }
                final List<Picked> result = picked;
                final String failure = error;
                ResultReturner.returnData(this, answerIntent, new ResultReturner.ResultJsonWriter() {
                    @Override
                    public void writeJson(JsonWriter out) throws Exception {
                        if (failure != null) {
                            out.beginObject().name("error").value(failure).endObject();
                            return;
                        }
                        out.beginArray();
                        for (Picked p : result) {
                            out.beginObject()
                                    .name("name").value(p.name)
                                    .name("mime").value(p.mime)
                                    .name("size").value(p.size)
                                    .name("path").value(p.path)
                                    .endObject();
                        }
                        out.endArray();
                    }
                });
                runOnUiThread(this::finish);
            }).start();
        }

        static List<Picked> copyAll(Context context, List<Uri> uris, File dir) throws Exception {
            List<Picked> result = new ArrayList<>();
            if (uris.isEmpty()) return result;
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new Exception("cannot create " + dir);
            }
            Set<String> used = new HashSet<>();
            for (Uri uri : uris) {
                Picked p = new Picked();
                p.name = "document";
                try (Cursor c = context.getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst() && !c.isNull(0)) p.name = c.getString(0);
                }
                p.name = p.name.replace('/', '_').replace('\0', '_');
                if (p.name.isEmpty() || p.name.startsWith(".")) p.name = "document" + p.name;
                String base = p.name;
                for (int n = 2; !used.add(p.name); n++) p.name = n + "-" + base;
                p.mime = context.getContentResolver().getType(uri);
                if (p.mime == null) p.mime = "application/octet-stream";
                File file = new File(dir, p.name);
                long size = 0;
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(file)) {
                    if (in == null) throw new Exception("cannot open " + uri);
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                        size += read;
                    }
                }
                p.size = size;
                p.path = file.getAbsolutePath();
                result.add(p);
                Logger.logDebug(LOG_TAG, "copied " + uri + " to " + p.path);
            }
            return result;
        }
    }
}

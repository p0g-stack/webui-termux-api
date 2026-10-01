package com.termux.api.apis;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipData.Item;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.io.PrintWriter;

/**
 * Upstream reads the clipboard from the receiver. Since Android 10 only the focused app
 * (or the IME) may read it, so this background app got nothing (webui fork): on Android 10+
 * a read is answered by {@link ClipboardReadActivity}, an invisible activity that reads once
 * it has window focus and finishes. Writes need no focus and stay as upstream.
 */
public class ClipboardAPI {

    private static final String LOG_TAG = "ClipboardAPI";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        boolean version2 = "2".equals(intent.getStringExtra("api_version"));
        boolean read = version2 ? !intent.getBooleanExtra("set", false) : intent.getStringExtra("text") == null;
        if (read && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent startIntent = new Intent(context, ClipboardReadActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ResultReturner.copyIntentExtras(intent, startIntent);
            context.startActivity(startIntent);
            return;
        }
        final ClipData clipData = read ? clipboard.getPrimaryClip() : null;

        if (version2) {
            boolean set = intent.getBooleanExtra("set", false);
            if (set) {
                ResultReturner.returnData(apiReceiver, intent, new ResultReturner.WithStringInput() {
                    @Override
                    protected boolean trimInput() {
                        return false;
                    }

                    @Override
                    public void writeResult(PrintWriter out) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", inputString));
                    }
                });
            } else {
                ResultReturner.returnData(apiReceiver, intent, out -> printClip(context, clipData, out));
            }
        } else {
            final String newClipText = intent.getStringExtra("text");
            if (newClipText != null) {
                // Set clip.
                clipboard.setPrimaryClip(ClipData.newPlainText("", newClipText));
            }

            ResultReturner.returnData(apiReceiver, intent, out -> {
                if (newClipText == null) {
                    // Get clip.
                    printClip(context, clipData, out);
                }
            });
        }
    }

    static void printClip(Context context, ClipData clipData, PrintWriter out) {
        if (clipData == null) {
            out.print("");
            return;
        }
        int itemCount = clipData.getItemCount();
        for (int i = 0; i < itemCount; i++) {
            Item item = clipData.getItemAt(i);
            CharSequence text = item.coerceToText(context);
            if (!TextUtils.isEmpty(text)) {
                out.print(text);
            }
        }
    }

    /** Reads the clipboard once it has window focus, answers, and finishes. */
    public static class ClipboardReadActivity extends Activity {

        private boolean answered;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            answered = savedInstanceState != null && savedInstanceState.getBoolean("answered");
            if (answered) finish();
        }

        @Override
        protected void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean("answered", answered);
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (!hasFocus || answered) return;
            answered = true;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            final ClipData clipData = clipboard.getPrimaryClip();
            final Context context = getApplicationContext();
            ResultReturner.returnData(this, getIntent(), out -> printClip(context, clipData, out));
            finish();
            overridePendingTransition(0, 0);
        }
    }
}

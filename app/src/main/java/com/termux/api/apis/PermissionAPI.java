package com.termux.api.apis;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonWriter;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports or requests runtime permissions of this app (webui fork).
 *
 * Extras: --esa permissions <p1,p2,...> (full names, e.g. android.permission.CAMERA)
 * and --ez request true to show Android's own dialog for those not yet granted.
 *
 * Answers one JSON object mapping each permission to "granted", "denied" or, after a
 * request, "permanentlyDenied" (denied and Android will not ask again). The app only
 * passes Android's answer on; what to show after a denial is the caller's.
 *
 * android.permission.POST_NOTIFICATIONS is special: this app targets an SDK before
 * Android 13, where Android does not let the app request it. Android asks by itself
 * instead, once, when the app starts an activity from a launcher intent after creating a
 * notification channel; so a request does exactly that ({@link NotificationPromptActivity}).
 * Before Android 13 the answer is whether notifications are enabled.
 */
public class PermissionAPI {

    private static final String LOG_TAG = "PermissionAPI";

    private static final String PERMISSIONS_EXTRA = "permissions";
    private static final String REQUEST_EXTRA = "request";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        final String[] permissions = intent.getStringArrayExtra(PERMISSIONS_EXTRA);
        if (permissions == null || permissions.length == 0) {
            ResultReturner.returnData(apiReceiver, intent, out -> out.println("ERROR: no permissions passed"));
            return;
        }

        final ArrayList<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (!isGranted(context, permission)) {
                missing.add(permission);
            }
        }

        if (intent.getBooleanExtra(REQUEST_EXTRA, false) && missing.contains(NOTIFICATIONS)) {
            if (missing.size() > 1) {
                ResultReturner.returnData(apiReceiver, intent, out -> out.println(
                        "ERROR: request " + NOTIFICATIONS + " on its own"));
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Nothing to ask: the user turned notifications off in Settings.
                final Map<String, String> result = new LinkedHashMap<>();
                result.put(NOTIFICATIONS, "permanentlyDenied");
                ResultReturner.returnData(apiReceiver, intent, writer(result));
                return;
            }
            Intent startIntent = new Intent(context, NotificationPromptActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ResultReturner.copyIntentExtras(intent, startIntent);
            context.startActivity(startIntent);
            return;
        }

        if (!intent.getBooleanExtra(REQUEST_EXTRA, false) || missing.isEmpty()) {
            final Map<String, String> result = new LinkedHashMap<>();
            for (String permission : permissions) {
                result.put(permission, missing.contains(permission) ? "denied" : "granted");
            }
            ResultReturner.returnData(apiReceiver, intent, writer(result));
            return;
        }

        Intent startIntent = new Intent(context, PermissionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PERMISSIONS_EXTRA, permissions);
        ResultReturner.copyIntentExtras(intent, startIntent);
        context.startActivity(startIntent);
    }

    static final String NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    static boolean isGranted(Context context, String permission) {
        if (NOTIFICATIONS.equals(permission) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager.areNotificationsEnabled();
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static ResultReturner.ResultJsonWriter writer(final Map<String, String> result) {
        return new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                for (Map.Entry<String, String> e : result.entrySet()) {
                    out.name(e.getKey()).value(e.getValue());
                }
                out.endObject();
            }
        };
    }

    /** Shows Android's permission dialog and answers with its result. */
    public static class PermissionActivity extends Activity {

        private static final String LOG_TAG = "PermissionActivity";

        private boolean requested;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            requested = savedInstanceState != null && savedInstanceState.getBoolean("requested");
        }

        @Override
        protected void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean("requested", requested);
        }

        @Override
        protected void onResume() {
            super.onResume();
            if (requested) return;
            requested = true;
            String[] permissions = getIntent().getStringArrayExtra(PERMISSIONS_EXTRA);
            Logger.logDebug(LOG_TAG, "requesting " + android.text.TextUtils.join(",", permissions));
            requestPermissions(permissions, 1);
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            String[] asked = getIntent().getStringArrayExtra(PERMISSIONS_EXTRA);
            final Map<String, String> result = new LinkedHashMap<>();
            for (String permission : asked) {
                if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    result.put(permission, "granted");
                } else if (permissions.length == 0) {
                    // The request was interrupted (another request, or the activity was
                    // recreated): Android gave no answer.
                    result.put(permission, "denied");
                } else {
                    result.put(permission, shouldShowRequestPermissionRationale(permission)
                            ? "denied" : "permanentlyDenied");
                }
            }
            ResultReturner.returnData(this, getIntent(), writer(result));
            finish();
        }
    }

    /**
     * Lets Android show its own notification prompt (Android 13+, apps targeting an
     * earlier SDK): it creates the notification channel and is started from a launcher
     * intent, which is when Android asks. It answers once the prompt closes, or, when
     * Android shows none (the user already answered, so it will not ask again), shortly
     * after it got focus.
     */
    public static class NotificationPromptActivity extends Activity {

        private static final long NO_PROMPT_MS = 1500;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean answered;
        private boolean prompted;

        private final Runnable answerIfNoPrompt = () -> answer(false);

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager.getNotificationChannel(NotificationAPI.CHANNEL_ID) == null) {
                manager.createNotificationChannel(new NotificationChannel(NotificationAPI.CHANNEL_ID,
                        NotificationAPI.CHANNEL_TITLE, NotificationManager.IMPORTANCE_DEFAULT));
            }
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (answered) return;
            if (!hasFocus) {
                // Android's prompt is in front.
                prompted = true;
                handler.removeCallbacks(answerIfNoPrompt);
            } else if (prompted) {
                answer(true);
            } else {
                handler.postDelayed(answerIfNoPrompt, NO_PROMPT_MS);
            }
        }

        private void answer(boolean afterPrompt) {
            if (answered) return;
            answered = true;
            final Map<String, String> result = new LinkedHashMap<>();
            // Either way a denial is final: Android asks a legacy app only once.
            result.put(NOTIFICATIONS, isGranted(this, NOTIFICATIONS) ? "granted" : "permanentlyDenied");
            Logger.logDebug("NotificationPrompt", (afterPrompt ? "after prompt: " : "no prompt: ") + result);
            ResultReturner.returnData(this, getIntent(), writer(result));
            finish();
            overridePendingTransition(0, 0);
        }

        @Override
        protected void onDestroy() {
            handler.removeCallbacks(answerIfNoPrompt);
            super.onDestroy();
        }
    }
}

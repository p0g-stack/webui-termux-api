package com.termux.api.apis;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
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
 * <p>Extras: --esa permissions <p1,p2,...> (full names, e.g. android.permission.CAMERA) and --ez
 * request true to show Android's own dialog for those not yet granted.
 *
 * <p>Answers one JSON object mapping each permission to "granted", "denied" or, after a request,
 * "permanentlyDenied" (denied and Android will not ask again). The app only passes Android's answer
 * on; what to show after a denial is the caller's.
 *
 * <p>Android 13+ notifications use the normal runtime permission dialog. On older Android releases
 * their state comes from NotificationManager. Broad storage on Android 11+ uses the system's
 * all-files access settings, not obsolete storage runtime permissions. Results always reflect
 * Android's current grant state.
 */
public class PermissionAPI {

    private static final String LOG_TAG = "PermissionAPI";

    private static final String PERMISSIONS_EXTRA = "permissions";
    private static final String REQUEST_EXTRA = "request";

    public static void onReceive(
            TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        final String[] permissions = intent.getStringArrayExtra(PERMISSIONS_EXTRA);
        if (permissions == null || permissions.length == 0) {
            ResultReturner.returnData(
                    apiReceiver, intent, out -> out.println("ERROR: no permissions passed"));
            return;
        }

        final ArrayList<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (!isGranted(context, permission)) {
                missing.add(permission);
            }
        }

        if (!intent.getBooleanExtra(REQUEST_EXTRA, false) || missing.isEmpty()) {
            final Map<String, String> result = new LinkedHashMap<>();
            for (String permission : permissions) {
                result.put(permission, missing.contains(permission) ? "denied" : "granted");
            }
            ResultReturner.returnData(apiReceiver, intent, writer(result));
            return;
        }

        Intent startIntent =
                new Intent(context, PermissionActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(PERMISSIONS_EXTRA, permissions);
        ResultReturner.copyIntentExtras(intent, startIntent);
        context.startActivity(startIntent);
    }

    static final String NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    static boolean isStoragePermission(String permission) {
        return Manifest.permission.MANAGE_EXTERNAL_STORAGE.equals(permission)
                || Manifest.permission.READ_EXTERNAL_STORAGE.equals(permission)
                || Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission);
    }

    static String runtimePermission(String permission) {
        if (Build.VERSION.SDK_INT >= 36 && Manifest.permission.BODY_SENSORS.equals(permission)) {
            return "android.permission.health.READ_HEART_RATE";
        }
        return permission;
    }

    static boolean isGranted(Context context, String permission) {
        if (NOTIFICATIONS.equals(permission)
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager.areNotificationsEnabled();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isStoragePermission(permission)) {
            return Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission(runtimePermission(permission))
                == PackageManager.PERMISSION_GRANTED;
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

        private static final int RUNTIME_REQUEST = 1;
        private static final int STORAGE_REQUEST = 2;
        private static final int BACKGROUND_LOCATION_REQUEST = 3;
        private int stage;
        private final Map<String, String> denied = new LinkedHashMap<>();

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState != null) {
                stage = savedInstanceState.getInt("stage");
                Bundle answers = savedInstanceState.getBundle("denied");
                if (answers != null) {
                    for (String permission : answers.keySet()) {
                        denied.put(permission, answers.getString(permission));
                    }
                }
            }
        }

        @Override
        protected void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putInt("stage", stage);
            Bundle answers = new Bundle();
            for (Map.Entry<String, String> answer : denied.entrySet()) {
                answers.putString(answer.getKey(), answer.getValue());
            }
            outState.putBundle("denied", answers);
        }

        @Override
        protected void onResume() {
            super.onResume();
            if (stage == 0) requestNext();
        }

        private void requestNext() {
            String[] permissions = getIntent().getStringArrayExtra(PERMISSIONS_EXTRA);
            if (stage == 0) {
                stage = RUNTIME_REQUEST;
                ArrayList<String> runtime = new ArrayList<>();
                for (String permission : permissions) {
                    if (isGranted(this, permission)) continue;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && (isStoragePermission(permission)
                                    || Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(
                                            permission))) continue;
                    if (NOTIFICATIONS.equals(permission)
                            && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) continue;
                    String name = runtimePermission(permission);
                    if (!runtime.contains(name)) runtime.add(name);
                }
                // Android 12+ requires coarse and fine location in the same request.
                if (runtime.contains(Manifest.permission.ACCESS_FINE_LOCATION)
                        && !runtime.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    runtime.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                }
                if (!runtime.isEmpty()) {
                    requestPermissions(runtime.toArray(new String[0]), RUNTIME_REQUEST);
                    return;
                }
            }
            if (stage == RUNTIME_REQUEST) {
                stage = STORAGE_REQUEST;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    for (String permission : permissions) {
                        if (isStoragePermission(permission) && !isGranted(this, permission)) {
                            startActivityForResult(
                                    new Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:" + getPackageName())),
                                    STORAGE_REQUEST);
                            return;
                        }
                    }
                }
            }
            if (stage == STORAGE_REQUEST) {
                stage = BACKGROUND_LOCATION_REQUEST;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    for (String permission : permissions) {
                        if (Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission)
                                && !isGranted(this, permission)) {
                            startActivityForResult(
                                    new Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:" + getPackageName())),
                                    BACKGROUND_LOCATION_REQUEST);
                            return;
                        }
                    }
                }
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (String permission : permissions) {
                result.put(
                        permission,
                        isGranted(this, permission)
                                ? "granted"
                                : denied.getOrDefault(
                                        runtimePermission(permission),
                                        NOTIFICATIONS.equals(permission)
                                                        && Build.VERSION.SDK_INT
                                                                < Build.VERSION_CODES.TIRAMISU
                                                ? "permanentlyDenied"
                                                : "denied"));
            }
            ResultReturner.returnData(this, getIntent(), writer(result));
            finish();
        }

        @Override
        public void onRequestPermissionsResult(
                int requestCode, String[] permissions, int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode != RUNTIME_REQUEST) return;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    denied.put(
                            permissions[i],
                            shouldShowRequestPermissionRationale(permissions[i])
                                    ? "denied"
                                    : "permanentlyDenied");
                }
            }
            requestNext();
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == STORAGE_REQUEST || requestCode == BACKGROUND_LOCATION_REQUEST) {
                requestNext();
            }
        }
    }
}

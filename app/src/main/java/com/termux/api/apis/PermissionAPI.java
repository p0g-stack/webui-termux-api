package com.termux.api.apis;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
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

        Intent startIntent = new Intent(context, PermissionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PERMISSIONS_EXTRA, permissions);
        ResultReturner.copyIntentExtras(intent, startIntent);
        context.startActivity(startIntent);
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
}

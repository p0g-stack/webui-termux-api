package com.termux.api.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.settings.preferences.AppSharedPreferences;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_API_APP;

/**
 * WebUI fork replacement for termux-shared's {@code WebuiAPIAppSharedPreferences}.
 *
 * Upstream builds the preferences from {@code createPackageContext("com.termux.api")}, i.e. the
 * real Termux:API package. This app is "com.webui.termux.api" with its own uid, so that would
 * either fail (real Termux:API not installed) or point at another app's private data directory.
 * This class uses this app's own context instead. Logic otherwise copied from termux-shared
 * (Copyright Termux, GPLv3).
 */
public class WebuiAPIAppSharedPreferences extends AppSharedPreferences {

    private WebuiAPIAppSharedPreferences(@NonNull Context context) {
        super(context,
            SharedPreferenceUtils.getPrivateSharedPreferences(context,
                TermuxConstants.TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
            SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
                TermuxConstants.TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION));
    }

    @Nullable
    public static WebuiAPIAppSharedPreferences build(@Nullable final Context context) {
        if (context == null) return null;
        Context appContext = context.getApplicationContext();
        return new WebuiAPIAppSharedPreferences(appContext != null ? appContext : context);
    }

    /** {@code exitAppOnError} is kept for call-site compatibility; own context never fails. */
    @Nullable
    public static WebuiAPIAppSharedPreferences build(@Nullable final Context context, final boolean exitAppOnError) {
        return build(context);
    }

    public int getLogLevel(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
        else
            return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel, boolean commitToFile) {
        logLevel = Logger.setLogLevel(context, logLevel);
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_API_APP.KEY_LOG_LEVEL, logLevel, commitToFile);
    }

    public int getLastPendingIntentRequestCode() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_API_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE, TERMUX_API_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE);
    }

    public void setLastPendingIntentRequestCode(int lastPendingIntentRequestCode) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_API_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE, lastPendingIntentRequestCode, true);
    }

}

package com.termux.api;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.termux.shared.logger.Logger;

/**
 * Keeps this app running while the module's root helper runs (webui fork).
 *
 * Android 14+ freezes cached apps, and upstream's {@link KeepAliveService} is a plain
 * background service, which does not stop that. Like the Termux app, this is a foreground
 * service with a silent, low-priority notification ("&lt;app label&gt; is running") and, on
 * request, a partial wake lock. Without the notification permission it still runs, unshown.
 *
 * Root starts it when the root helper winds up and stops it at the helper's idle shutdown:
 * <pre>
 * am start-foreground-service --user 0 -n &lt;pkg&gt;/com.termux.api.RootHelperService [--ez wakelock true]
 * am stopservice --user 0 -n &lt;pkg&gt;/com.termux.api.RootHelperService
 * </pre>
 * Starting it again only changes the wake lock.
 */
public class RootHelperService extends Service {

    private static final String LOG_TAG = "RootHelperService";
    private static final String CHANNEL_ID = "root_helper";
    private static final int NOTIFICATION_ID = 0x7e6b;
    public static final String WAKELOCK_EXTRA = "wakelock";

    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        boolean wantWakeLock = intent != null && intent.getBooleanExtra(WAKELOCK_EXTRA, false);
        setWakeLock(wantWakeLock);
        Logger.logDebug(LOG_TAG, "running, wake lock " + wantWakeLock);
        // The root helper decides the lifetime: never restart on our own.
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        String text = label + " is running";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, text, NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        Notification notification = builder
                .setContentTitle(text)
                .setSmallIcon(R.drawable.ic_event_note_black_24dp)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void setWakeLock(boolean held) {
        if (held && wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":root-helper");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } else if (!held && wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        setWakeLock(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

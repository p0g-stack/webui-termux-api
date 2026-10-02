package com.termux.api;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.termux.shared.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
 *
 * It never outlives its holders, so a root helper that is killed (the manager closed or
 * swiped away) cannot leave it behind: on start it listens on the abstract socket
 * {@code <pkg>/hold}. The root helper connects right after starting it and keeps the
 * connection open for its whole life; it sends nothing. The service stops itself when the
 * last holder's connection ends (EOF or error, as when the kernel closes a dead process's
 * fds), or when no holder is connected {@value #HOLD_WAIT_MS} ms after a start. Only root
 * (uid 0) and this app may hold it. {@code am stopservice} still stops it at once.
 */
public class RootHelperService extends Service {

    private static final String LOG_TAG = "RootHelperService";
    private static final String CHANNEL_ID = "root_helper";
    private static final int NOTIFICATION_ID = 0x7e6b;
    public static final String WAKELOCK_EXTRA = "wakelock";

    /** How long after a start the service waits for a holder. */
    static final long HOLD_WAIT_MS = 10_000;

    private PowerManager.WakeLock wakeLock;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final List<LocalSocket> holders = new ArrayList<>();
    private LocalServerSocket holdServer;
    private volatile boolean destroyed;

    /**
     * The latest start. Stops pass the id they saw, so a start that arrives in between
     * (a new root helper) keeps the service.
     */
    private volatile int lastStartId;

    private final Runnable stopIfUnheld = () -> {
        synchronized (lock) {
            if (!holders.isEmpty()) return;
        }
        Logger.logInfo(LOG_TAG, "no holder, stopping");
        stopSelf(lastStartId);
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        startInForeground();
        boolean wantWakeLock = intent != null && intent.getBooleanExtra(WAKELOCK_EXTRA, false);
        setWakeLock(wantWakeLock);
        Logger.logDebug(LOG_TAG, "running, wake lock " + wantWakeLock);
        listenForHolders();
        main.removeCallbacks(stopIfUnheld);
        main.postDelayed(stopIfUnheld, HOLD_WAIT_MS);
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

    /** The hold socket's name in the abstract namespace. */
    String holdAddress() {
        return getPackageName() + "/hold";
    }

    private boolean listening;

    private void listenForHolders() {
        if (listening) return;
        listening = true;
        final int appUid = getApplicationInfo().uid;
        new Thread(() -> {
            LocalServerSocket server = bind();
            if (server == null) return;
            synchronized (lock) {
                if (destroyed) {
                    try {
                        server.close();
                    } catch (IOException ignored) {
                    }
                    return;
                }
                holdServer = server;
            }
            acceptHolders(server, appUid);
        }, "root-helper-hold").start();
    }

    /**
     * Binds the hold socket. A service that just stopped may still own the name for a
     * moment, so binding is retried briefly; without the socket nothing can hold the
     * service and the start's wait stops it.
     */
    private LocalServerSocket bind() {
        IOException last = null;
        for (int i = 0; i < 20 && !destroyed; i++) {
            try {
                return new LocalServerSocket(holdAddress());
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    return null;
                }
            }
        }
        if (last != null) Logger.logStackTraceWithMessage(LOG_TAG, "cannot listen on " + holdAddress(), last);
        return null;
    }

    private void acceptHolders(LocalServerSocket server, int appUid) {
        while (!destroyed) {
            final LocalSocket holder;
            try {
                holder = server.accept();
            } catch (IOException e) {
                if (!destroyed) Logger.logStackTraceWithMessage(LOG_TAG, "accept", e);
                return;
            }
            if (destroyed) {
                closeQuietly(holder);
                return;
            }
            int uid;
            try {
                uid = holder.getPeerCredentials().getUid();
            } catch (IOException e) {
                uid = -1;
            }
            if (uid != 0 && uid != appUid) {
                Logger.logWarn(LOG_TAG, "refused holder uid " + uid);
                closeQuietly(holder);
                continue;
            }
            synchronized (lock) {
                holders.add(holder);
            }
            new Thread(() -> watch(holder), "root-helper-holder").start();
        }
    }

    /** Waits for a holder's connection to end; the last one to end stops the service. */
    private void watch(LocalSocket holder) {
        try (InputStream in = holder.getInputStream()) {
            byte[] buf = new byte[64];
            //noinspection StatementWithEmptyBody
            while (in.read(buf) != -1) {
            }
        } catch (IOException ignored) {
            // A reset is an end like EOF.
        }
        closeQuietly(holder);
        boolean last;
        synchronized (lock) {
            last = holders.remove(holder) && holders.isEmpty();
        }
        if (last && !destroyed) {
            final int startId = lastStartId;
            Logger.logInfo(LOG_TAG, "last holder gone, stopping");
            main.post(() -> stopSelf(startId));
        }
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(stopIfUnheld);
        LocalServerSocket server;
        List<LocalSocket> held;
        synchronized (lock) {
            server = holdServer;
            holdServer = null;
            held = new ArrayList<>(holders);
            holders.clear();
        }
        if (server != null) {
            // close() does not wake a blocked accept() on Android: connect once to end it.
            new Thread(() -> {
                try (LocalSocket wake = new LocalSocket()) {
                    wake.connect(new LocalSocketAddress(holdAddress()));
                } catch (IOException ignored) {
                }
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }, "root-helper-unhold").start();
        }
        for (LocalSocket holder : held) closeQuietly(holder);
        setWakeLock(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

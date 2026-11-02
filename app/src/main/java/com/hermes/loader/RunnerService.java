package com.hermes.loader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps the agent process alive while the app is in the background and gives
 * the user a way to stop it from the notification shade.
 */
public class RunnerService extends Service {

    private static final String CHANNEL_ID = "agent";
    private static final int NOTIFICATION_ID = 42;
    private static final String ACTION_STOP = "com.hermes.loader.action.STOP";

    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, RunnerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, RunnerService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Thread stop = new Thread(() -> {
                try {
                    Session.get().stop();
                    AuxDashboard.get().stop();
                } finally {
                    new Handler(Looper.getMainLooper()).post(() -> stopSelfResult(startId));
                }
            }, "notification-stop");
            stop.setDaemon(true);
            stop.start();
            return START_NOT_STICKY;
        }

        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireWakeLock() {
        if (wakeLock != null) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesLoader:agent");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_description));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Intent stop = new Intent(this, RunnerService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(0, getString(R.string.notif_stop), stopAction);
        b.setCategory(Notification.CATEGORY_SERVICE);
        return b.build();
    }
}

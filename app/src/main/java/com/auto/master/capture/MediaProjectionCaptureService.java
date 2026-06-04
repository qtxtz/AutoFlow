package com.auto.master.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.auto.master.R;

public class MediaProjectionCaptureService extends Service {
    private static final String TAG = "MediaProjectionCapture";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 2;
    private static final Object FOREGROUND_LOCK = new Object();
    private static volatile boolean foregroundStarted;

    public static void ensureStarted(Context context) {
        if (context == null || foregroundStarted) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, MediaProjectionCaptureService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent);
            } else {
                appContext.startService(intent);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "start media projection foreground service failed", e);
        }
    }

    public static boolean awaitForegroundStarted(long timeoutMs) {
        if (foregroundStarted) {
            return true;
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (FOREGROUND_LOCK) {
            while (!foregroundStarted) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    FOREGROUND_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return foregroundStarted;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!foregroundStarted) {
            startInForeground();
        }
        return START_STICKY;
    }

    private void startInForeground() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕采集中")
                .setContentText("自动化任务正在使用屏幕采集")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            synchronized (FOREGROUND_LOCK) {
                foregroundStarted = true;
                FOREGROUND_LOCK.notifyAll();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "enter media projection foreground failed", e);
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        synchronized (FOREGROUND_LOCK) {
            foregroundStarted = false;
            FOREGROUND_LOCK.notifyAll();
        }
        super.onDestroy();
    }
}

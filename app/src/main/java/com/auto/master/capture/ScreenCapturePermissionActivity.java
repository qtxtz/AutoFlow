package com.auto.master.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Window;

import androidx.annotation.Nullable;

import com.auto.master.auto.ActivityHolder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transient activity used by overlay-only flows to request MediaProjection permission
 * and then resume the original capture action in the service.
 */
public class ScreenCapturePermissionActivity extends Activity {
    private static final String TAG = "ScreenCapturePermission";
    private static final int REQ_MEDIA_PROJECTION = 0x4A71;
    private static final List<Callback> CALLBACKS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean REQUEST_IN_FLIGHT = new AtomicBoolean(false);
    private boolean completed;

    public interface Callback {
        void onResult(boolean granted);
    }

    public static void request(Context context, @Nullable Callback callback) {
        if (context == null) {
            if (callback != null) {
                callback.onResult(false);
            }
            return;
        }
        if (ScreenCapture.hasProjectionPermission()) {
            if (callback != null) {
                callback.onResult(true);
            }
            return;
        }
        if (callback != null) {
            CALLBACKS.add(callback);
        }
        if (!REQUEST_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        Activity resumedActivity = ActivityHolder.getResumedActivity();
        Context launchContext = canLaunchFrom(resumedActivity) ? resumedActivity : context;
        Intent intent = new Intent(launchContext, ScreenCapturePermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if (!(launchContext instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        try {
            launchContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "start permission activity failed", e);
            REQUEST_IN_FLIGHT.set(false);
            for (Callback pending : CALLBACKS) {
                if (pending != null) {
                    pending.onResult(false);
                }
            }
            CALLBACKS.clear();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        overridePendingTransition(0, 0);
        try {
            startActivityForResult(ScreenCapture.createProjectionIntent(this), REQ_MEDIA_PROJECTION);
        } catch (Exception e) {
            Log.e(TAG, "request MediaProjection failed", e);
            notifyCallbacks(false);
            finishWithoutAnimation();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean granted = false;
        if (requestCode == REQ_MEDIA_PROJECTION
                && resultCode == RESULT_OK
                && data != null) {
            ScreenCapture.saveProjectionPermission(resultCode, data);
            MediaProjectionCaptureService.ensureStarted(this);
            ScreenCaptureManager manager = ScreenCaptureManager.getInstance();
            manager.init(this);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                boolean started = manager.startCapture(resultCode, data);
                notifyCallbacks(started);
                finishWithoutAnimation();
            }, 250L);
            return;
        }
        notifyCallbacks(granted);
        finishWithoutAnimation();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && !completed) {
            notifyCallbacks(false);
        }
        super.onDestroy();
    }

    private void notifyCallbacks(boolean granted) {
        completed = true;
        REQUEST_IN_FLIGHT.set(false);
        for (Callback callback : CALLBACKS) {
            if (callback != null) {
                callback.onResult(granted);
            }
        }
        CALLBACKS.clear();
    }

    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    private static boolean canLaunchFrom(@Nullable Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }
}

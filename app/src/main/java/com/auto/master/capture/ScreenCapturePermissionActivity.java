package com.auto.master.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;

import androidx.annotation.Nullable;

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
        Intent intent = new Intent(context, ScreenCapturePermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            context.startActivity(intent);
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
            ScreenCaptureManager manager = ScreenCaptureManager.getInstance();
            manager.init(this);
            granted = manager.startCapture(resultCode, data);
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
}

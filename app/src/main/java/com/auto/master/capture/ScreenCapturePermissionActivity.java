package com.auto.master.capture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import com.auto.master.auto.AutoAccessibilityService;

public class ScreenCapturePermissionActivity extends Activity {

    static final String EXTRA_AUTO_CONFIRM = "auto_confirm";
    private static final String TAG = "CapturePermissionAct";
    private static final int REQ_MEDIA_PROJECTION = 6407;
    private boolean requestStarted = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        requestMediaProjection();
    }

    private void requestMediaProjection() {
        if (requestStarted) {
            return;
        }
        requestStarted = true;
        boolean autoConfirm = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_CONFIRM, false);
        if (autoConfirm) {
            AutoAccessibilityService.armMediaProjectionAutoConfirm();
        }
        try {
            startActivityForResult(ScreenCapture.createProjectionIntent(this), REQ_MEDIA_PROJECTION);
        } catch (Throwable t) {
            Log.e(TAG, "start media projection request failed", t);
            ScreenCapture.deliverProjectionPermissionResult(false);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean granted = requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null;
        if (granted) {
            ScreenCapture.saveProjectionPermission(resultCode, data);
        }
        finish();
        overridePendingTransition(0, 0);
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        handler.postDelayed(() -> ScreenCapture.deliverProjectionPermissionResult(granted), 80L);
    }
}

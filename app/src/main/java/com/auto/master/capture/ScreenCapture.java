package com.auto.master.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.auto.master.auto.ActivityHolder;
import com.auto.master.utils.AdaptivePollingController;
import com.auto.master.utils.OpenCVHelper;

import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;

public final class ScreenCapture {

    private static final String TAG = "ScreenCapture";
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 3000L;
    private static final long DEFAULT_WAIT_INTERVAL_MS = 80L;
    private static final long AUTO_PERMISSION_WAIT_TIMEOUT_MS = 9000L;
    private static final long POST_PERMISSION_CAPTURE_DELAY_MS = 260L;
    private static final long POST_SESSION_START_SETTLE_MS = 140L;

    // 静态保存授权（保持兼容）
    private static volatile int sResultCode = 0;
    private static volatile Intent sResultData = null;
    private static volatile long sLastProjectionGrantUptimeMs = 0L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object PERMISSION_LOCK = new Object();
    private static final List<ProjectionPermissionCallback> PENDING_PERMISSION_CALLBACKS = new ArrayList<>();
    private static boolean permissionRequestInFlight = false;

    public interface ProjectionPermissionCallback {
        void onResult(boolean granted);
    }

    public static Intent createProjectionIntent(Activity a) {
        return createProjectionIntent((Context) a);
    }

    public static Intent createProjectionIntent(Context context) {
        MediaProjectionManager mpm = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return mpm.createScreenCaptureIntent();
    }

    public static void saveProjectionPermission(int resultCode, Intent data) {
        sResultCode = resultCode;
        sResultData = data;
        sLastProjectionGrantUptimeMs = SystemClock.uptimeMillis();
    }

    public static boolean hasProjectionPermission() {
        return sResultCode != 0 && sResultData != null;
    }

    public static void requestProjectionPermission(Context context,
                                                   boolean autoConfirm,
                                                   ProjectionPermissionCallback callback) {
        if (context == null) {
            if (callback != null) {
                MAIN.post(() -> callback.onResult(false));
            }
            return;
        }
        if (hasProjectionPermission()) {
            if (callback != null) {
                MAIN.post(() -> callback.onResult(true));
            }
            return;
        }

        boolean shouldStartActivity;
        synchronized (PERMISSION_LOCK) {
            if (callback != null) {
                PENDING_PERMISSION_CALLBACKS.add(callback);
            }
            shouldStartActivity = !permissionRequestInFlight;
            permissionRequestInFlight = true;
        }
        if (!shouldStartActivity) {
            return;
        }

        Intent intent = new Intent(context.getApplicationContext(), ScreenCapturePermissionActivity.class);
        intent.putExtra(ScreenCapturePermissionActivity.EXTRA_AUTO_CONFIRM, autoConfirm);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.getApplicationContext().startActivity(intent);
    }

    static void deliverProjectionPermissionResult(boolean granted) {
        List<ProjectionPermissionCallback> callbacks;
        synchronized (PERMISSION_LOCK) {
            permissionRequestInFlight = false;
            callbacks = new ArrayList<>(PENDING_PERMISSION_CALLBACKS);
            PENDING_PERMISSION_CALLBACKS.clear();
        }
        for (ProjectionPermissionCallback callback : callbacks) {
            if (callback != null) {
                MAIN.post(() -> callback.onResult(granted));
            }
        }
    }

    private static void waitForPostPermissionCooldown() {
        long remainingMs = (sLastProjectionGrantUptimeMs + POST_PERMISSION_CAPTURE_DELAY_MS)
                - SystemClock.uptimeMillis();
        if (remainingMs > 0L) {
            SystemClock.sleep(remainingMs);
        }
    }

    private static boolean ensureCaptureSession(Activity activity) {
        if (!hasProjectionPermission()) {
            Context context = activity != null ? activity : ActivityHolder.getTopActivity();
            if (!requestProjectionPermissionBlocking(context, true, AUTO_PERMISSION_WAIT_TIMEOUT_MS)) {
                Log.e(TAG, "缺少录屏权限");
                return false;
            }
        }
        waitForPostPermissionCooldown();
        ScreenCaptureManager manager = ScreenCaptureManager.getInstance();
        if (activity != null) {
            manager.init(activity);
        } else {
            Activity topActivity = ActivityHolder.getTopActivity();
            if (topActivity != null) {
                manager.init(topActivity);
            }
        }
        if (manager.isRunning()) {
            return true;
        }
        if (sResultData == null) {
            Log.e(TAG, "录屏权限数据为空");
            return false;
        }
        boolean started = manager.startCapture(sResultCode, sResultData);
        if (!started) {
            Log.e(TAG, "启动录屏失败");
        } else {
            SystemClock.sleep(POST_SESSION_START_SETTLE_MS);
        }
        return started;
    }

    public static boolean requestProjectionPermissionBlocking(Context context,
                                                              boolean autoConfirm,
                                                              long timeoutMs) {
        if (hasProjectionPermission()) {
            return true;
        }
        final Object lock = new Object();
        final boolean[] done = {false};
        final boolean[] granted = {false};
        requestProjectionPermission(context, autoConfirm, result -> {
            synchronized (lock) {
                granted[0] = result;
                done[0] = true;
                lock.notifyAll();
            }
        });

        long deadline = SystemClock.uptimeMillis() + Math.max(1000L, timeoutMs);
        synchronized (lock) {
            while (!done[0] && SystemClock.uptimeMillis() < deadline) {
                try {
                    lock.wait(Math.max(50L, deadline - SystemClock.uptimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return granted[0] || hasProjectionPermission();
    }

    /**
     * 统一截图入口 实际开启录屏的 入口 初始化
     */
    public static Mat captureNow(Activity activity, Method method, String outName) {
        Log.d(TAG, "captureNow: method=" + method + ", name=" + outName);

        if (method != Method.MEDIA_PROJECTION_SINGLE_SHOOT && method != Method.MEDIA_PROJECTION) {
            Log.e(TAG, "不支持的截图方式或服务未启动");
            return null;
        }

        ScreenCaptureManager manager = ScreenCaptureManager.getInstance();
        if (!ensureCaptureSession(activity)) {
            return null;
        }
        return waitForLatestFrame(null, DEFAULT_WAIT_TIMEOUT_MS, DEFAULT_WAIT_INTERVAL_MS);
    }

    public static Mat captureRoiNow(Activity activity, Method method, Rect roi, String outName) {
        Log.d(TAG, "captureRoiNow: method=" + method + ", roi=" + roi + ", name=" + outName);

        if (roi == null || roi.isEmpty()) {
            return captureNow(activity, method, outName);
        }
        if (method != Method.MEDIA_PROJECTION_SINGLE_SHOOT && method != Method.MEDIA_PROJECTION) {
            Log.e(TAG, "不支持的截图方式或服务未启动");
            return null;
        }

        ScreenCaptureManager manager = ScreenCaptureManager.getInstance();
        if (!ensureCaptureSession(activity)) {
            return null;
        }
        return waitForLatestFrame(roi, DEFAULT_WAIT_TIMEOUT_MS, DEFAULT_WAIT_INTERVAL_MS);
    }


    /**
     * 这里能拿到正确图片的前提是 1、有权限 2、captureNow开启了 3、尺寸纠正了
     * @return
     */
    public static Mat getSingleBitMapWhileInContinous(boolean clone){

        if (!ScreenCaptureManager.getInstance().isRunning()) {
            ensureCaptureSession(ActivityHolder.getTopActivity());
        }

        /**
         * 如果clone 为 true ，多线程安全，则消费者自行release
         * 如果clone为 false，只允许单线程使用，消费者无需release。
         */
        Mat mat = ScreenCaptureManager.getInstance().getLatestMat(clone);


        return mat;

    }

    public static Mat getSingleBitMapRoiWhileInContinous(Rect roi, boolean clone) {
        if (roi == null || roi.isEmpty()) {
            return getSingleBitMapWhileInContinous(clone);
        }
        if (!ScreenCaptureManager.getInstance().isRunning()) {
            ensureCaptureSession(ActivityHolder.getTopActivity());
        }
        return ScreenCaptureManager.getInstance().getLatestRoiMat(roi, clone);
    }

    public static int getFrameSequence() {
        return ScreenCaptureManager.getInstance().getFrameSeq();
    }

    public static Mat waitForLatestFrame(Rect roi, long timeoutMs, long intervalMs) {
        if (!ScreenCaptureManager.getInstance().isRunning()) {
            ensureCaptureSession(ActivityHolder.getTopActivity());
        }
        AdaptivePollingController pollingController = AdaptivePollingController.forTemplateMatch();
        long safeTimeoutMs = Math.max(intervalMs, timeoutMs);
        long safeIntervalMs = Math.max(10L, intervalMs);
        long deadline = SystemClock.uptimeMillis() + safeTimeoutMs;
        Mat fallback = null;
        while (SystemClock.uptimeMillis() <= deadline) {
            Mat frame = (roi == null || roi.isEmpty())
                    ? pollingController.acquireFrame()
                    : pollingController.acquireFrame(roi);
            if (frame != null && !frame.empty()) {
                if (pollingController.hasFreshFrame()) {
                    return frame;
                }
                if (fallback == null) {
                    fallback = frame;
                }
            }
            SystemClock.sleep(safeIntervalMs);
        }
        return fallback;
    }

    public static Bitmap captureLatestBitmap(Rect roi, long timeoutMs, long intervalMs) {
        Mat mat = waitForLatestFrame(roi, timeoutMs, intervalMs);
        if (mat == null || mat.empty()) {
            return null;
        }
        return OpenCVHelper.getInstance().matToBitmap(mat);
    }

    public enum Method {
        MEDIA_PROJECTION,
        MEDIA_PROJECTION_SINGLE_SHOOT,
        PIXEL_COPY,
        A11Y_DUMP
    }
}

package com.auto.master.Task.Handler.OperationHandler;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.auto.master.Task.Operation.AppCloseOperation;
import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OperationContext;
import com.auto.master.auto.AutoAccessibilityService;

import java.util.HashMap;
import java.util.Map;

public class AppCloseOperationHandler extends OperationHandler {

    private static final String TAG = "AppCloseOpHandler";
    private static final long DEFAULT_CLOSE_DELAY_MS = 800L;

    AppCloseOperationHandler() {
        this.setType(23);
    }

    @Override
    public boolean handle(MetaOperation obj, OperationContext ctx) {
        AppCloseOperation operation = (AppCloseOperation) obj;
        AutoAccessibilityService svc = AutoAccessibilityService.get();
        if (svc == null) {
            return false;
        }

        Map<String, Object> inputMap = operation.getInputMap();
        String packageName = asString(inputMap.get(MetaOperation.APP_PACKAGE));
        String appLabel = asString(inputMap.get(MetaOperation.APP_LABEL));
        boolean returnHome = asBoolean(inputMap.get(MetaOperation.APP_CLOSE_RETURN_HOME), true);
        boolean killBackground = asBoolean(inputMap.get(MetaOperation.APP_CLOSE_KILL_BACKGROUND), true);
        long closeDelayMs = asLong(inputMap.get(MetaOperation.APP_CLOSE_DELAY_MS), DEFAULT_CLOSE_DELAY_MS);

        if (TextUtils.isEmpty(packageName)) {
            Log.w(TAG, "Missing app package");
            return false;
        }
        if (packageName.equals(svc.getPackageName())) {
            Log.w(TAG, "Refuse to close self package: " + packageName);
            fillResponse(ctx, obj, packageName, appLabel, false, false, false, false);
            return false;
        }

        String currentPackage = getCurrentPackageName(svc);
        boolean wasForeground = packageName.equals(currentPackage);
        boolean homePressed = false;
        if (returnHome && wasForeground) {
            homePressed = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            if (!homePressed) {
                Log.w(TAG, "HOME action failed before closing: " + packageName);
            }
            sleepSafely(Math.min(Math.max(closeDelayMs, 250L), 1200L));
        }

        boolean killRequested = false;
        if (killBackground) {
            ActivityManager activityManager = (ActivityManager) svc.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                try {
                    activityManager.killBackgroundProcesses(packageName);
                    killRequested = true;
                } catch (Throwable t) {
                    Log.w(TAG, "killBackgroundProcesses failed: " + packageName, t);
                }
            }
        }

        boolean success = homePressed || killRequested || (!wasForeground && !killBackground);
        fillResponse(ctx, obj, packageName, appLabel, success, wasForeground, homePressed, killRequested);
        return success && sleepSafely(closeDelayMs);
    }

    private void fillResponse(OperationContext ctx,
                              MetaOperation obj,
                              String packageName,
                              String appLabel,
                              boolean success,
                              boolean wasForeground,
                              boolean homePressed,
                              boolean killRequested) {
        Map<String, Object> response = new HashMap<>();
        response.put(MetaOperation.RESULT, success);
        response.put(MetaOperation.APP_PACKAGE, packageName);
        response.put(MetaOperation.APP_LABEL, appLabel);
        response.put("wasForeground", wasForeground);
        response.put("homePressed", homePressed);
        response.put("killRequested", killRequested);
        ctx.currentResponse = response;
        ctx.lastOperation = obj;
    }

    private String getCurrentPackageName(AutoAccessibilityService svc) {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return "";
        }
        return String.valueOf(root.getPackageName());
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("1".equals(text)) {
                return true;
            }
            if ("0".equals(text)) {
                return false;
            }
            if (!text.isEmpty()) {
                return Boolean.parseBoolean(text);
            }
        }
        return defaultValue;
    }

    private long asLong(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (Exception ignored) {
            }
        }
        return defaultValue;
    }

    private boolean sleepSafely(long delayMs) {
        long safeDelay = Math.max(0L, delayMs);
        if (safeDelay == 0L) {
            return true;
        }
        try {
            Thread.sleep(safeDelay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

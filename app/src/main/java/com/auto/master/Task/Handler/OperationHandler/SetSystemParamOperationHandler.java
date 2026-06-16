package com.auto.master.Task.Handler.OperationHandler;

import android.content.Context;
import android.util.Log;

import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OperationContext;
import com.auto.master.auto.AutoAccessibilityService;
import com.auto.master.capture.ScreenCaptureManager;
import com.auto.master.utils.RuntimeDisplayConfig;
import com.auto.master.utils.SystemRuntimeConfig;

import java.util.HashMap;
import java.util.Map;

public class SetSystemParamOperationHandler extends OperationHandler {

    private static final String TAG = "SetSystemParamOp";

    SetSystemParamOperationHandler() {
        this.setType(29);
    }

    @Override
    public boolean handle(MetaOperation obj, OperationContext ctx) {
        Map<String, Object> inputMap = obj.getInputMap();
        String paramKey = inputMap == null ? "" : toString(inputMap.get(MetaOperation.SYS_PARAM_KEY));
        String paramValue = inputMap == null ? "" : toString(inputMap.get(MetaOperation.SYS_PARAM_VALUE));

        boolean success = false;
        switch (paramKey) {
            case MetaOperation.SYS_PARAM_CAPTURE_SCALE:
                success = applyCaptureScale(paramValue);
                break;
            case MetaOperation.SYS_PARAM_COUNTDOWN_COLOR:
                success = applyCountdownColor(paramValue);
                break;
            case MetaOperation.SYS_PARAM_GESTURE_COLOR:
                success = applyGestureColor(paramValue);
                break;
            case MetaOperation.SYS_PARAM_RUNTIME_LOG_ENABLED:
                success = applyRuntimeLogEnabled(paramValue);
                break;
            default:
                Log.w(TAG, "未知系统参数: " + paramKey);
        }

        if (ctx != null) {
            Map<String, Object> res = new HashMap<>();
            res.put(MetaOperation.MATCHED, success);
            res.put("paramKey", paramKey);
            res.put("paramValue", paramValue);
            ctx.currentResponse = res;
            ctx.lastOperation = obj;
        }
        return true;
    }

    private boolean applyCaptureScale(String value) {
        try {
            float scale = Float.parseFloat(value.trim());
            scale = Math.max(0.25f, Math.min(1.0f, scale));
            float prev = ScreenCaptureManager.CAPTURE_SCALE;
            syncCaptureScaleConfig(scale);
            if (Math.abs(scale - prev) >= 0.001f) {
                ScreenCaptureManager.getInstance().setCaptureScale(scale);
                MatchMaptemplateOperationHandler.clearMatchPlanCache();
                MatchtemplateOperationHandler.clearRandomRoiCache();
                if (ScreenCaptureManager.getInstance().isRunning()) {
                    android.os.SystemClock.sleep(1500L);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "设置采集倍率失败", e);
            return false;
        }
    }

    private void syncCaptureScaleConfig(float scale) {
        AutoAccessibilityService svc = AutoAccessibilityService.get();
        if (svc == null) {
            return;
        }
        Context ctx = svc.getApplicationContext();
        SystemRuntimeConfig cfg = SystemRuntimeConfig.load(ctx);
        cfg.captureScale = scale;
        cfg.save(ctx);
        cfg.applyToRuntime();
    }

    private boolean applyCountdownColor(String value) {
        try {
            int color = (int) Long.parseLong(value.replace("#", ""), 16);
            RuntimeDisplayConfig.COUNTDOWN_FILL_COLOR = color;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "设置倒计时颜色失败: " + value, e);
            return false;
        }
    }

    private boolean applyGestureColor(String value) {
        try {
            int color = (int) Long.parseLong(value.replace("#", ""), 16);
            RuntimeDisplayConfig.GESTURE_STROKE_COLOR = color;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "设置手势颜色失败: " + value, e);
            return false;
        }
    }

    private boolean applyRuntimeLogEnabled(String value) {
        AutoAccessibilityService svc = AutoAccessibilityService.get();
        if (svc == null) {
            return false;
        }
        String normalized = value == null ? "" : value.trim();
        boolean enabled = "true".equalsIgnoreCase(normalized)
                || "1".equals(normalized)
                || "on".equalsIgnoreCase(normalized)
                || "yes".equalsIgnoreCase(normalized)
                || "enable".equalsIgnoreCase(normalized);
        Context context = svc.getApplicationContext();
        SystemRuntimeConfig cfg = SystemRuntimeConfig.load(context);
        cfg.runtimeLogEnabled = enabled;
        cfg.save(context);
        return true;
    }

    private static String toString(Object obj) {
        return obj == null ? "" : obj.toString().trim();
    }
}

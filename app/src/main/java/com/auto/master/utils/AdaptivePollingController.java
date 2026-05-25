package com.auto.master.utils;

import android.graphics.Rect;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.auto.master.capture.ScreenCapture;
import com.auto.master.Task.Operation.MetaOperation;

import org.opencv.core.Mat;

import java.util.Map;

/**
 * 自适应轮询控制器：
 * - 前期保持较快响应
 * - 连续未命中后自动降频，降低 CPU / 截图链压力
 * - 默认不复用旧帧，优先保证截图新鲜度
 */
public final class AdaptivePollingController {

    private final long fastIntervalMs;
    private final long mediumIntervalMs;
    private final long slowIntervalMs;
    private final long warmupWindowMs;
    private final long slowdownWindowMs;
    private final int mediumMissThreshold;
    private final int slowMissThreshold;

    private final long createdAtMs;
    private int consecutiveMisses = 0;
    private int lastObservedFrameSeq = -1;
    private boolean lastAcquireReturnedFreshFrame = false;

    private AdaptivePollingController(long fastIntervalMs,
                                      long mediumIntervalMs,
                                      long slowIntervalMs,
                                      long warmupWindowMs,
                                      long slowdownWindowMs,
                                      int mediumMissThreshold,
                                      int slowMissThreshold) {
        this.fastIntervalMs = fastIntervalMs;
        this.mediumIntervalMs = mediumIntervalMs;
        this.slowIntervalMs = slowIntervalMs;
        this.warmupWindowMs = warmupWindowMs;
        this.slowdownWindowMs = slowdownWindowMs;
        this.mediumMissThreshold = mediumMissThreshold;
        this.slowMissThreshold = slowMissThreshold;
        this.createdAtMs = SystemClock.uptimeMillis();
    }

    public static AdaptivePollingController forTemplateMatch() {
        return new AdaptivePollingController(
                220L, 380L, 600L,
                800L, 2500L,
                2, 5
        );
    }

    public static AdaptivePollingController forTemplateMatch(@Nullable Map<String, Object> inputMap) {
        return fromInputMap(inputMap, 220L, 380L, 600L, 800L, 2500L, 2, 5);
    }

    public static AdaptivePollingController forMatchMap() {
        return new AdaptivePollingController(
                220L, 380L, 560L,
                800L, 2500L,
                2, 5
        );
    }

    public static AdaptivePollingController forMatchMap(@Nullable Map<String, Object> inputMap) {
        return fromInputMap(inputMap, 220L, 380L, 560L, 800L, 2500L, 2, 5);
    }

    public static AdaptivePollingController forColorCheck() {
        return new AdaptivePollingController(
                220L, 420L, 700L,
                800L, 2500L,
                2, 4
        );
    }

    public static AdaptivePollingController forColorCheck(@Nullable Map<String, Object> inputMap) {
        return fromInputMap(inputMap, 220L, 420L, 700L, 800L, 2500L, 2, 4);
    }

    private static AdaptivePollingController fromInputMap(@Nullable Map<String, Object> inputMap,
                                                          long defaultFastIntervalMs,
                                                          long defaultMediumIntervalMs,
                                                          long defaultSlowIntervalMs,
                                                          long warmupWindowMs,
                                                          long slowdownWindowMs,
                                                          int mediumMissThreshold,
                                                          int slowMissThreshold) {
        long fastIntervalMs = parseIntervalMs(inputMap, MetaOperation.POLL_FAST_INTERVAL_MS, defaultFastIntervalMs);
        long mediumIntervalMs = parseIntervalMs(inputMap, MetaOperation.POLL_MEDIUM_INTERVAL_MS, defaultMediumIntervalMs);
        long slowIntervalMs = parseIntervalMs(inputMap, MetaOperation.POLL_SLOW_INTERVAL_MS, defaultSlowIntervalMs);
        return new AdaptivePollingController(
                fastIntervalMs, mediumIntervalMs, slowIntervalMs,
                warmupWindowMs, slowdownWindowMs,
                mediumMissThreshold, slowMissThreshold
        );
    }

    private static long parseIntervalMs(@Nullable Map<String, Object> inputMap, String key, long fallback) {
        if (inputMap == null) {
            return fallback;
        }
        Object raw = inputMap.get(key);
        long value = fallback;
        if (raw instanceof Number) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof String) {
            try {
                value = Long.parseLong(((String) raw).trim());
            } catch (Exception ignored) {
                value = fallback;
            }
        }
        return Math.max(10L, Math.min(value, 5000L));
    }

    @Nullable
    public Mat acquireFrame() {
        Mat frame = ScreenCapture.getSingleBitMapWhileInContinous(false);
        lastAcquireReturnedFreshFrame = updateFreshState(frame);
        return frame;
    }

    @Nullable
    public Mat acquireFrame(@Nullable Rect roi) {
        if (roi == null || roi.isEmpty()) {
            return acquireFrame();
        }
        Mat frame = ScreenCapture.getSingleBitMapRoiWhileInContinous(roi, false);
        lastAcquireReturnedFreshFrame = updateFreshState(frame);
        return frame;
    }

    public boolean hasFreshFrame() {
        return lastAcquireReturnedFreshFrame;
    }

    public void onMiss() {
        consecutiveMisses++;
    }

    public void onHit() {
        consecutiveMisses = 0;
    }

    public void sleepUntilNextIteration(long loopStartMs) {
        long targetIntervalMs = currentIntervalMs(SystemClock.uptimeMillis());
        long elapsedMs = SystemClock.uptimeMillis() - loopStartMs;
        if (elapsedMs < targetIntervalMs) {
            SystemClock.sleep(targetIntervalMs - elapsedMs);
        }
    }

    private boolean updateFreshState(@Nullable Mat frame) {
        if (frame == null || frame.empty()) {
            return false;
        }
        int frameSeq = ScreenCapture.getFrameSequence();
        if (frameSeq <= 0) {
            return false;
        }
        if (frameSeq == lastObservedFrameSeq) {
            return false;
        }
        lastObservedFrameSeq = frameSeq;
        return true;
    }

    private long currentIntervalMs(long nowMs) {
        long elapsedMs = nowMs - createdAtMs;
        if (consecutiveMisses >= slowMissThreshold || elapsedMs >= slowdownWindowMs) {
            return slowIntervalMs;
        }
        if (consecutiveMisses >= mediumMissThreshold || elapsedMs >= warmupWindowMs) {
            return mediumIntervalMs;
        }
        return fastIntervalMs;
    }
}

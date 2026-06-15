package com.auto.master.floatwin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import com.auto.master.utils.RuntimeDisplayConfig;

import java.util.Locale;

/**
 * 倒计时悬浮条：基于 SystemClock.uptimeMillis() 计时，与 Thread.sleep 同源，
 * 不受 ANIMATOR_DURATION_SCALE 影响，始终精准匹配实际延迟时长。
 *
 * 显示：条形从满→空，文字显示剩余时间（3.0s → 0.0s）。
 */
public class DelayCountdownOverlayView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect  = new RectF();
    private final RectF fillRect   = new RectF();

    private long startUptimeMs = 0L;
    private long durationMs    = 0L;
    private boolean running    = false;

    private final Choreographer choreographer = Choreographer.getInstance();
    private Choreographer.FrameCallback frameCallback;

    public DelayCountdownOverlayView(Context context) {
        super(context);

        trackPaint.setColor(0x33FFFFFF);
        trackPaint.setStyle(Paint.Style.FILL);

        fillPaint.setColor(RuntimeDisplayConfig.COUNTDOWN_FILL_COLOR);
        fillPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(sp(11.5f));

        setAlpha(0.9f);
        setVisibility(GONE);
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * 启动倒计时。
     *
     * @param durationMs     总时长（ms），与 Thread.sleep 传入的值相同
     * @param startElapsedMs 已经过的时间（通常为 0，syncDelayState 时传实际值）
     * @param onReady        启动后同步回调（用于释放 CountDownLatch）
     */
    public void start(long durationMs, long startElapsedMs, Runnable onReady) {
        stopInternal();
        this.durationMs   = Math.max(1L, durationMs);
        long elapsed      = Math.max(0L, Math.min(startElapsedMs, this.durationMs));
        this.startUptimeMs = SystemClock.uptimeMillis() - elapsed;
        this.running       = true;
        setVisibility(VISIBLE);
        scheduleFrame();

        // 立即回调，不等下一帧 —— 保证 CountDownLatch 在 sleep 开始前释放
        if (onReady != null) onReady.run();
    }

    public void start(long durationMs, long startElapsedMs) {
        start(durationMs, startElapsedMs, null);
    }

    public void stop() {
        stopInternal();
        setVisibility(GONE);
    }

    // ── draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running || durationMs <= 0L || getWidth() <= 0) return;

        long elapsed   = Math.min(durationMs, SystemClock.uptimeMillis() - startUptimeMs);
        long remaining = Math.max(0L, durationMs - elapsed);
        float fraction = elapsed / (float) durationMs;

        fillPaint.setColor(RuntimeDisplayConfig.COUNTDOWN_FILL_COLOR);

        float r = dp(5f);
        trackRect.set(0f, 0f, getWidth(), getHeight());
        canvas.drawRoundRect(trackRect, r, r, trackPaint);

        // 倒计时条：满→空
        fillRect.set(0f, 0f, getWidth() * fraction, getHeight());
        canvas.drawRoundRect(fillRect, r, r, fillPaint);

        // 剩余时间文字
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = trackRect.centerY() - (fm.bottom - fm.top) / 2f - fm.top;
        canvas.drawText(fmtRemaining(elapsed), getWidth() / 2f, baseline, textPaint);
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private void scheduleFrame() {
        frameCallback = frameTimeNanos -> {
            if (!running) return;
            long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
            if (elapsed >= durationMs) {
                running = false;
                setVisibility(GONE);
                return;
            }
            invalidate();
            choreographer.postFrameCallback(frameCallback);
        };
        choreographer.postFrameCallback(frameCallback);
    }

    private void stopInternal() {
        running = false;
        if (frameCallback != null) {
            choreographer.removeFrameCallback(frameCallback);
            frameCallback = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopInternal();
        super.onDetachedFromWindow();
    }

    // ── formatting ────────────────────────────────────────────────────────────

    private String fmtRemaining(long ms) {
        if (ms <= 0L) return "0.0s";
        int minutes = (int) ((ms % 3_600_000L) / 60_000L);
        int seconds = (int) ((ms % 60_000L) / 1_000L);
        int tenths  = (int) ((ms % 1_000L) / 100L);
        if (minutes > 0) {
            return String.format(Locale.getDefault(), "%d:%02d.%ds", minutes, seconds, tenths);
        }
        return String.format(Locale.getDefault(), "%d.%ds", seconds, tenths);
    }

    // ── util ──────────────────────────────────────────────────────────────────

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}

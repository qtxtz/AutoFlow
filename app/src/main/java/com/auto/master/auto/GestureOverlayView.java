package com.auto.master.auto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.auto.master.utils.RuntimeDisplayConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GestureOverlayView extends FrameLayout {
    private Paint paint;
    private Map<Integer, Path> activePaths = new HashMap<>();
    private Map<Integer, GestureStroke> activeStrokes = new HashMap<>();
    private List<GestureStroke> allStrokes = new ArrayList<>();
    private long startTime;
    private OnGestureRecordedListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable finishRunnable;
    private long idleFinishDelayMs = 0L;
    private boolean finished;
    private OnStrokeGroupCompletedListener strokeGroupCompletedListener;
    private boolean isWaitingResume = false;
    private int currentGroupStartIndex = 0;
    private long pausedAt = 0L; // 暂停等待 replay 时的时刻，用于补偿 startTime

    // 遮罩 View（最稳定方式：独立子 View 画全屏灰色）
    public View maskView;
    private static final int MASK_COLOR = 0x88000000; // 50% 灰

    public interface OnGestureRecordedListener {
        void onGestureRecorded(GestureNode node);
    }

    public interface OnStrokeGroupCompletedListener {
        void onStrokeGroupCompleted(GestureNode strokeNode, Runnable resumeRecording);
    }

    public GestureOverlayView(Context context) {
        super(context);

        // 初始化轨迹画笔
        paint = new Paint();
        paint.setColor(RuntimeDisplayConfig.GESTURE_STROKE_COLOR);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(45f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setAntiAlias(true);

        // 创建遮罩层（全屏灰色）
        maskView = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawColor(MASK_COLOR);
                Log.d("GestureOverlay", "遮罩 onDraw 已执行，尺寸: " + getWidth() + "x" + getHeight());
            }
            
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                Log.d("GestureOverlay", "遮罩尺寸变化: " + w + "x" + h);
            }
        };
        LayoutParams maskParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(maskView, maskParams);
        maskView.setVisibility(VISIBLE); // 默认显示遮罩

        Log.d("GestureOverlay", "新建 GestureOverlayView 实例");
    }

    public void resetForRecording(long idleFinishDelayMs) {
        cancelPendingFinish();
        activePaths.clear();
        activeStrokes.clear();
        allStrokes.clear();
        startTime = 0L;
        finished = false;
        this.idleFinishDelayMs = Math.max(0L, idleFinishDelayMs);
        strokeGroupCompletedListener = null;
        isWaitingResume = false;
        currentGroupStartIndex = 0;
        pausedAt = 0L;
        showMask();
        setVisibility(VISIBLE);
        invalidate();
    }

    // 显示遮罩（还没按下时）
    public void showMask() {
        maskView.setVisibility(VISIBLE);
        maskView.invalidate();
    }

    // 隐藏遮罩（开始滑动后）
    public void hideMask() {
        maskView.setVisibility(GONE);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        paint.setColor(RuntimeDisplayConfig.GESTURE_STROKE_COLOR);
        for (Path path : activePaths.values()) {
            canvas.drawPath(path, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (finished) {
            return true;
        }
        if (isWaitingResume) return true;
        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                cancelPendingFinish();
                int pointerIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndex);
                float x = ev.getX(pointerIndex);
                float y = ev.getY(pointerIndex);

                if (allStrokes.isEmpty()) {
                    startTime = System.currentTimeMillis();
                    Log.d("GestureOverlay", "开始录制，按下坐标: (" + x + "," + y + ")");
                }
                if (startTime <= 0L) {
                    startTime = System.currentTimeMillis();
                }

                Path path = new Path();
                path.moveTo(x, y);
                activePaths.put(pointerId, path);

                List<PointF> points = new ArrayList<>();
                points.add(new PointF(x, y));
                long relativeStart = Math.max(0L, System.currentTimeMillis() - startTime);
                GestureStroke stroke = new GestureStroke(pointerId, points, relativeStart);
                activeStrokes.put(pointerId, stroke);
                allStrokes.add(stroke);
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                // 第一次移动时隐藏遮罩
                if (!activePaths.isEmpty() && maskView.getVisibility() == VISIBLE) {
                    hideMask();
                    Log.d("GestureOverlay", "开始滑动，隐藏遮罩");
                }

                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int pointerId = ev.getPointerId(i);
                    Path path = activePaths.get(pointerId);
                    GestureStroke stroke = activeStrokes.get(pointerId);
                    if (path != null) {
                        float x = ev.getX(i);
                        float y = ev.getY(i);
                        path.lineTo(x, y);

                        if (stroke != null) {
                            stroke.points.add(new PointF(x, y));
                            stroke.duration = Math.max(1L,
                                    System.currentTimeMillis() - startTime - stroke.relativeStartTime);
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int pointerIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndex);

                activePaths.remove(pointerId);
                GestureStroke stroke = activeStrokes.remove(pointerId);
                if (stroke != null) {
                    stroke.duration = Math.max(1L,
                            System.currentTimeMillis() - startTime - stroke.relativeStartTime);
                }
                if (activePaths.isEmpty()) {
                    if (strokeGroupCompletedListener != null) {
                        // 提取本轮 stroke group（从 currentGroupStartIndex 到末尾），时间归零
                        List<GestureStroke> groupStrokes = new ArrayList<>(
                            allStrokes.subList(currentGroupStartIndex, allStrokes.size()));
                        long baseTime = groupStrokes.isEmpty() ? 0 : groupStrokes.get(0).relativeStartTime;
                        List<GestureStroke> normalized = new ArrayList<>();
                        for (GestureStroke s : groupStrokes) {
                            normalized.add(new GestureStroke(s.pointerId, s.points,
                                Math.max(0L, s.relativeStartTime - baseTime), s.duration));
                        }
                        long groupDuration = 1L;
                        for (GestureStroke s : normalized) {
                            long end = s.relativeStartTime + s.duration;
                            if (end > groupDuration) groupDuration = end;
                        }
                        GestureNode strokeNode = new GestureNode(normalized, groupDuration);
                        isWaitingResume = true;
                        pausedAt = System.currentTimeMillis(); // 记录暂停时刻
                        int nextGroupStart = allStrokes.size();
                        Runnable resumeRecording = () -> {
                            // 补偿 startTime：把 replay + 等待占用的时间从计时中扣除
                            // 这样下一步 stroke 的 relativeStartTime 只计算"遮罩出现→手指按下"的时间
                            if (pausedAt > 0 && startTime > 0) {
                                startTime += System.currentTimeMillis() - pausedAt;
                            }
                            pausedAt = 0L;
                            isWaitingResume = false;
                            currentGroupStartIndex = nextGroupStart;
                            showMask();
                            if (idleFinishDelayMs > 0L) scheduleFinish();
                        };
                        strokeGroupCompletedListener.onStrokeGroupCompleted(strokeNode, resumeRecording);
                    } else if (idleFinishDelayMs > 0L && action != MotionEvent.ACTION_CANCEL) {
                        scheduleFinish();
                    } else {
                        finishRecordingNow();
                    }
                }
                break;
            }

        }
        // ── 所有事件都转发给底层 ──


        invalidate();
        return true;
    }

    private void scheduleFinish() {
        cancelPendingFinish();
        finishRunnable = this::finishRecordingNow;
        handler.postDelayed(finishRunnable, idleFinishDelayMs);
        Log.d("GestureOverlay", "等待连续手势，空闲 " + idleFinishDelayMs + "ms 后结束");
    }

    private void cancelPendingFinish() {
        if (finishRunnable != null) {
            handler.removeCallbacks(finishRunnable);
            finishRunnable = null;
        }
    }

    public void finishRecordingNow() {
        if (finished) {
            return;
        }
        finished = true;
        cancelPendingFinish();
        activePaths.clear();
        activeStrokes.clear();

        long duration = Math.max(1L, System.currentTimeMillis() - startTime);
        GestureNode node = new GestureNode(allStrokes, duration);
        if (listener != null) {
            listener.onGestureRecorded(node);
        }
        hideMask();
        setVisibility(GONE);
        Log.d("GestureOverlay", "录制结束，笔画: " + node.strokes.size() + "，时长: " + duration + "ms");
        invalidate();
    }



    public void setOnGestureRecordedListener(OnGestureRecordedListener l) {
        this.listener = l;
    }

    public void setOnStrokeGroupCompletedListener(OnStrokeGroupCompletedListener l) {
        this.strokeGroupCompletedListener = l;
    }

    // 内部类保持 public static
    public static class GestureStroke {
        public int pointerId;
        public List<PointF> points = new ArrayList<>();
        public long relativeStartTime;
        public long duration;

        public GestureStroke(int id, List<PointF> pts, long start) {
            this.pointerId = id;
            this.points = new ArrayList<>(pts);
            this.relativeStartTime = start;
            this.duration = 1L;
        }

        public GestureStroke(int id, List<PointF> pts, long start, long duration) {
            this.pointerId = id;
            this.points = new ArrayList<>(pts);
            this.relativeStartTime = start;
            this.duration = Math.max(1L, duration);
        }
    }

    public static class GestureNode {
        public List<GestureStroke> strokes;
        public long duration;
        public int type;

        public GestureNode(List<GestureStroke> s, long d) {
            this.strokes = new ArrayList<>();
            if (s != null) {
                for (GestureStroke stroke : s) {
                    if (stroke == null || stroke.points == null || stroke.points.isEmpty()) {
                        continue;
                    }
                    long strokeDuration = stroke.duration > 0L
                            ? stroke.duration
                            : Math.max(1L, d - Math.max(0L, stroke.relativeStartTime));
                    this.strokes.add(new GestureStroke(
                            stroke.pointerId,
                            stroke.points,
                            Math.max(0L, stroke.relativeStartTime),
                            strokeDuration));
                }
            }
            this.duration = Math.max(1L, d);
            if (strokes.size() == 1) {
                type = strokes.get(0).points.size() > 2 ? 2 : 1;
            } else if (isSequentialGesture()) {
                type = 27;
            } else {
                type = 26;
            }
        }

        private boolean isSequentialGesture() {
            if (strokes.size() <= 1) {
                return false;
            }
            long latestStart = Long.MAX_VALUE;
            for (GestureStroke stroke : strokes) {
                if (stroke.relativeStartTime > 0L) {
                    latestStart = Math.min(latestStart, stroke.relativeStartTime);
                }
            }
            return latestStart != Long.MAX_VALUE;
        }
    }

    public static class PointF {
        public float x, y;

        public PointF(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}

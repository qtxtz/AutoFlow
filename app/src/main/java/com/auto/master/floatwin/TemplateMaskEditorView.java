package com.auto.master.floatwin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

public class TemplateMaskEditorView extends View {

    private static final int   MASK_LAYER_COLOR  = 0x88E53935;
    /** 每 bitmap 像素占多少视图 px 时开始显示网格 */
    private static final float GRID_THRESHOLD_PX = 6f;
    private static final float MAX_SCALE         = 48f;

    private final Paint bitmapPaint    = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskRectPaint  = new Paint();
    private final Paint maskPaint      = new Paint();
    private final Paint framePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 网格暗色层（黑色半透明，较粗），确保在白色背景上可见 */
    private final Paint gridDarkPaint  = new Paint();
    /** 网格亮色层（白色半透明，较细），确保在深色背景上可见 */
    private final Paint gridLightPaint = new Paint();

    private Bitmap templateBitmap;
    private Bitmap maskBitmap;
    private Canvas maskCanvas;

    private boolean eraseMode;
    private int brushSize = 6;

    // 变换：bitmap 坐标 → 视图坐标
    // viewX = bitmapX * scale + tx
    private float scale   = 1f;
    private float tx      = 0f;
    private float ty      = 0f;
    private float fitScale = 1f;

    // 触摸状态
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector      gestureDetector;
    private boolean isScaling   = false;
    private float   lastPanX    = -1f;
    private float   lastPanY    = -1f;
    private float   lastDrawBX  = Float.NaN;
    private float   lastDrawBY  = Float.NaN;

    public TemplateMaskEditorView(Context context) {
        super(context);
        scaleDetector   = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new TapListener());
        init();
    }

    public TemplateMaskEditorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scaleDetector   = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new TapListener());
        init();
    }

    private void init() {
        setBackgroundColor(0xFF18212D);
        maskPaint.setStyle(Paint.Style.FILL);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(1.5f);
        framePaint.setColor(0x80FFFFFF);
        gridDarkPaint.setStyle(Paint.Style.STROKE);
        gridDarkPaint.setStrokeWidth(1.5f);
        gridDarkPaint.setColor(0x55000000);  // 暗层：粗线，在白色背景可见
        gridLightPaint.setStyle(Paint.Style.STROKE);
        gridLightPaint.setStrokeWidth(0.6f);
        gridLightPaint.setColor(0x99FFFFFF); // 亮层：细线压在中央，在深色背景可见
    }

    // ── 公开 API ───────────────────────────────────────────────────────────────

    public void setTemplateBitmap(Bitmap bitmap, @Nullable Bitmap initialMask) {
        release();
        if (bitmap == null || bitmap.isRecycled()) return;
        templateBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        maskBitmap = Bitmap.createBitmap(
                templateBitmap.getWidth(), templateBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        maskCanvas = new Canvas(maskBitmap);
        if (initialMask != null && !initialMask.isRecycled()) copyInitialMask(initialMask);
        if (getWidth() > 0) resetView();
        invalidate();
    }

    private void copyInitialMask(Bitmap initialMask) {
        int w = Math.min(maskBitmap.getWidth(), initialMask.getWidth());
        int h = Math.min(maskBitmap.getHeight(), initialMask.getHeight());
        int[] pixels = new int[w * h];
        initialMask.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int v = Math.max(Color.red(p), Math.max(Color.green(p), Color.blue(p)));
            pixels[i] = v > 0 ? MASK_LAYER_COLOR : 0x00000000;
        }
        maskBitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    public void resetView() {
        if (templateBitmap == null) return;
        float viewW = getWidth()  - getPaddingLeft() - getPaddingRight();
        float viewH = getHeight() - getPaddingTop()  - getPaddingBottom();
        if (viewW <= 0 || viewH <= 0) return;
        fitScale = Math.min(viewW / templateBitmap.getWidth(), viewH / templateBitmap.getHeight());
        scale = fitScale;
        float imgW = templateBitmap.getWidth()  * scale;
        float imgH = templateBitmap.getHeight() * scale;
        tx = getPaddingLeft() + (viewW - imgW) / 2f;
        ty = getPaddingTop()  + (viewH - imgH) / 2f;
        invalidate();
    }

    /** 以视图中心为基准放大 1.5× */
    public void zoomIn() {
        applyZoom(1.5f);
    }

    /** 以视图中心为基准缩小到 2/3 */
    public void zoomOut() {
        applyZoom(1f / 1.5f);
    }

    private void applyZoom(float factor) {
        float focusX = getWidth()  / 2f;
        float focusY = getHeight() / 2f;
        float newScale = Math.max(fitScale * 0.5f, Math.min(MAX_SCALE, scale * factor));
        tx = focusX - (focusX - tx) * (newScale / scale);
        ty = focusY - (focusY - ty) * (newScale / scale);
        scale = newScale;
        clampTranslation();
        invalidate();
    }

    public void setEraseMode(boolean eraseMode) { this.eraseMode = eraseMode; }
    public boolean isEraseMode()                { return eraseMode; }

    public void setBrushSize(int brushSize) {
        this.brushSize = Math.max(1, Math.min(96, brushSize));
    }
    public int getBrushSize() { return brushSize; }

    public void clearMask() {
        if (maskCanvas != null) {
            maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            invalidate();
        }
    }

    public Bitmap exportMaskBitmap() {
        if (maskBitmap == null || maskBitmap.isRecycled()) return null;
        int w = maskBitmap.getWidth(), h = maskBitmap.getHeight();
        int[] pixels = new int[w * h];
        maskBitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = Color.alpha(pixels[i]) > 0 ? Color.WHITE : Color.BLACK;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    public boolean hasMaskPixels() {
        if (maskBitmap == null || maskBitmap.isRecycled()) return false;
        int w = maskBitmap.getWidth(), h = maskBitmap.getHeight();
        int[] pixels = new int[w * h];
        maskBitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int p : pixels) if (Color.alpha(p) > 0) return true;
        return false;
    }

    public void release() {
        if (templateBitmap != null && !templateBitmap.isRecycled()) templateBitmap.recycle();
        if (maskBitmap     != null && !maskBitmap.isRecycled())     maskBitmap.recycle();
        templateBitmap = null;
        maskBitmap     = null;
        maskCanvas     = null;
    }

    // ── 布局 ──────────────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (templateBitmap != null) {
            if (oldw == 0) resetView();   // 首次布局：居中适配
            else           clampTranslation(); // 尺寸变化：保持视角不超界
        }
    }

    // ── 绘制 ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (templateBitmap == null || maskBitmap == null) return;

        RectF dst = imageDstRect();
        canvas.drawBitmap(templateBitmap, null, dst, bitmapPaint);
        drawMask(canvas, dst);

        if (scale >= GRID_THRESHOLD_PX) drawPixelGrid(canvas);

        canvas.drawRect(dst, framePaint);
    }

    /**
     * 绘制 mask 层。
     * 缩放到像素可见级别（scale >= GRID_THRESHOLD_PX）时，逐像素用 drawRect 绘制，
     * 完全绕开 GPU bitmap 缩放插值，保证单个像素边界锐利；
     * 缩小时用 drawBitmap 快速路径。
     */
    private void drawMask(Canvas canvas, RectF dst) {
        if (maskBitmap == null) return;
        if (scale >= GRID_THRESHOLD_PX) {
            drawMaskPixelPerfect(canvas);
        } else {
            canvas.drawBitmap(maskBitmap, null, dst, bitmapPaint);
        }
    }

    private void drawMaskPixelPerfect(Canvas canvas) {
        int bw = maskBitmap.getWidth(), bh = maskBitmap.getHeight();
        float vl = getPaddingLeft(), vt = getPaddingTop();
        float vr = getWidth()  - getPaddingRight();
        float vb = getHeight() - getPaddingBottom();

        // 计算当前视口内可见的 bitmap 像素范围
        int startX = Math.max(0,      (int) Math.floor((vl - tx) / scale));
        int endX   = Math.min(bw - 1, (int) Math.floor((vr - tx) / scale));
        int startY = Math.max(0,      (int) Math.floor((vt - ty) / scale));
        int endY   = Math.min(bh - 1, (int) Math.floor((vb - ty) / scale));
        if (startX > endX || startY > endY) return;

        int w = endX - startX + 1, h = endY - startY + 1;
        int[] pixels = new int[w * h];
        maskBitmap.getPixels(pixels, 0, w, startX, startY, w, h);

        maskRectPaint.setStyle(Paint.Style.FILL);
        maskRectPaint.setColor(MASK_LAYER_COLOR);
        canvas.save();
        canvas.clipRect(vl, vt, vr, vb);
        for (int iy = 0; iy < h; iy++) {
            for (int ix = 0; ix < w; ix++) {
                if (Color.alpha(pixels[iy * w + ix]) > 0) {
                    int bx = startX + ix, by = startY + iy;
                    // 以屏幕坐标直接绘制矩形，无任何插值
                    canvas.drawRect(
                            bx       * scale + tx,
                            by       * scale + ty,
                            (bx + 1) * scale + tx,
                            (by + 1) * scale + ty,
                            maskRectPaint);
                }
            }
        }
        canvas.restore();
    }

    private RectF imageDstRect() {
        float imgW = templateBitmap.getWidth()  * scale;
        float imgH = templateBitmap.getHeight() * scale;
        return new RectF(tx, ty, tx + imgW, ty + imgH);
    }

    private void drawPixelGrid(Canvas canvas) {
        int bw = templateBitmap.getWidth();
        int bh = templateBitmap.getHeight();

        float vl = getPaddingLeft(), vt = getPaddingTop();
        float vr = getWidth() - getPaddingRight();
        float vb = getHeight() - getPaddingBottom();

        int startX = Math.max(0,  (int) Math.floor((vl - tx) / scale));
        int endX   = Math.min(bw, (int) Math.ceil ((vr - tx) / scale));
        int startY = Math.max(0,  (int) Math.floor((vt - ty) / scale));
        int endY   = Math.min(bh, (int) Math.ceil ((vb - ty) / scale));

        float y0 = startY * scale + ty, y1 = endY   * scale + ty;
        float x0 = startX * scale + tx, x1 = endX   * scale + tx;
        canvas.save();
        canvas.clipRect(vl, vt, vr, vb);
        // 每条线画两遍：先粗暗线（黑色衬底），再细亮线（白色压顶），任何背景下都可见
        for (int ix = startX; ix <= endX; ix++) {
            float vx = ix * scale + tx;
            canvas.drawLine(vx, y0, vx, y1, gridDarkPaint);
            canvas.drawLine(vx, y0, vx, y1, gridLightPaint);
        }
        for (int iy = startY; iy <= endY; iy++) {
            float vy = iy * scale + ty;
            canvas.drawLine(x0, vy, x1, vy, gridDarkPaint);
            canvas.drawLine(x0, vy, x1, vy, gridLightPaint);
        }
        canvas.restore();
    }

    // ── 触摸 ──────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (maskCanvas == null || templateBitmap == null) return false;

        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();

        if (event.getPointerCount() >= 2 || isScaling) {
            // 双指：缩放 + 平移
            handlePan(event, action);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isScaling = false;
                lastPanX  = -1f;
                lastPanY  = -1f;
            }
            lastDrawBX = Float.NaN;
            lastDrawBY = Float.NaN;
            return true;
        }

        // 单指：绘制
        float vx = event.getX(), vy = event.getY();
        float[] bp = viewToBitmap(vx, vy);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (bp != null) {
                    drawAt(bp[0], bp[1]);
                    lastDrawBX = bp[0];
                    lastDrawBY = bp[1];
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (bp != null) {
                    if (!Float.isNaN(lastDrawBX)) {
                        drawLine(lastDrawBX, lastDrawBY, bp[0], bp[1]);
                    } else {
                        drawAt(bp[0], bp[1]);
                    }
                    lastDrawBX = bp[0];
                    lastDrawBY = bp[1];
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastDrawBX = Float.NaN;
                lastDrawBY = Float.NaN;
                return true;
        }
        return true;
    }

    private void handlePan(MotionEvent event, int action) {
        // 多指重心作为平移锚点
        float cx = 0, cy = 0;
        int count = event.getPointerCount();
        for (int i = 0; i < count; i++) { cx += event.getX(i); cy += event.getY(i); }
        cx /= count; cy /= count;

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            lastPanX = cx; lastPanY = cy;
        } else if (action == MotionEvent.ACTION_MOVE && lastPanX >= 0) {
            tx += cx - lastPanX;
            ty += cy - lastPanY;
            clampTranslation();
            invalidate();
            lastPanX = cx; lastPanY = cy;
        } else if (lastPanX < 0) {
            lastPanX = cx; lastPanY = cy;
        }
    }

    private void clampTranslation() {
        if (templateBitmap == null) return;
        float imgW = templateBitmap.getWidth()  * scale;
        float imgH = templateBitmap.getHeight() * scale;
        float vl = getPaddingLeft(), vt = getPaddingTop();
        float vr = getWidth()  - getPaddingRight();
        float vb = getHeight() - getPaddingBottom();
        float viewW = vr - vl, viewH = vb - vt;

        float density = getResources().getDisplayMetrics().density;
        float minVisible = 40f * density; // 图片至少要有这么多 px 留在视图内

        // 水平
        if (imgW <= viewW) {
            // 图比视图小：允许在居中位置 ±半图宽范围内移动
            float cx = vl + (viewW - imgW) / 2f;
            tx = Math.max(cx - imgW / 2f, Math.min(cx + imgW / 2f, tx));
        } else {
            // 图比视图大：两侧至少保留 minVisible px 可见
            float m = Math.min(minVisible, imgW * 0.3f);
            // txMin: 图右边 = vl + m（图几乎划出左侧，保留 m）
            // txMax: 图左 = vr - m（图几乎划出右侧，保留 m）
            tx = Math.max(vl + m - imgW, Math.min(vr - m, tx));
        }

        // 垂直
        if (imgH <= viewH) {
            float cy = vt + (viewH - imgH) / 2f;
            ty = Math.max(cy - imgH / 2f, Math.min(cy + imgH / 2f, ty));
        } else {
            float m = Math.min(minVisible, imgH * 0.3f);
            ty = Math.max(vt + m - imgH, Math.min(vb - m, ty));
        }
    }

    @Nullable
    private float[] viewToBitmap(float vx, float vy) {
        if (templateBitmap == null || scale <= 0) return null;
        float bx = (vx - tx) / scale;
        float by = (vy - ty) / scale;
        if (bx < 0 || by < 0 || bx >= templateBitmap.getWidth() || by >= templateBitmap.getHeight()) {
            return null;
        }
        return new float[]{bx, by};
    }

    private void drawLine(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / Math.max(1, brushSize / 2f)));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            drawAt(x1 + dx * t, y1 + dy * t);
        }
    }

    private void drawAt(float bx, float by) {
        if (maskCanvas == null) return;
        if (eraseMode) {
            maskPaint.setColor(Color.TRANSPARENT);
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        } else {
            maskPaint.setColor(MASK_LAYER_COLOR);
            maskPaint.setXfermode(null);
        }
        if (brushSize == 1) {
            // 1px 模式：吸附到像素格，精确涂/擦单个像素
            int px = (int) bx, py = (int) by;
            maskCanvas.drawRect(px, py, px + 1, py + 1, maskPaint);
        } else {
            maskCanvas.drawCircle(bx, by, Math.max(0.5f, brushSize / 2f), maskPaint);
        }
        maskPaint.setXfermode(null);
        invalidate();
    }

    // ── 手势监听 ──────────────────────────────────────────────────────────────

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            isScaling = true;
            float focusX = detector.getFocusX(), focusY = detector.getFocusY();
            float newScale = Math.max(fitScale * 0.5f,
                    Math.min(MAX_SCALE, scale * detector.getScaleFactor()));
            // 以捏合焦点为中心缩放
            tx = focusX - (focusX - tx) * (newScale / scale);
            ty = focusY - (focusY - ty) * (newScale / scale);
            scale = newScale;
            clampTranslation();
            invalidate();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            // 捏合结束后短暂延迟才允许绘制，防止误触
            postDelayed(() -> isScaling = false, 120);
        }
    }

    private class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // 双击复位到 fit-to-view
            resetView();
            return true;
        }
    }
}

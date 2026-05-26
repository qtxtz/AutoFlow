package com.auto.master.floatwin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class TemplateMaskEditorView extends View {
    private static final int MASK_LAYER_COLOR = 0x88E53935;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private Bitmap templateBitmap;
    private Bitmap maskBitmap;
    private Canvas maskCanvas;
    private boolean eraseMode;
    private int brushSize = 6;
    private float lastX = -1f;
    private float lastY = -1f;

    public TemplateMaskEditorView(Context context) {
        super(context);
        init();
    }

    public TemplateMaskEditorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setBackgroundColor(0xFF18212D);
        maskPaint.setStyle(Paint.Style.FILL);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(2f);
        framePaint.setColor(0xFFFFFFFF);
    }

    public void setTemplateBitmap(Bitmap bitmap, @Nullable Bitmap initialMask) {
        release();
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        templateBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        maskBitmap = Bitmap.createBitmap(templateBitmap.getWidth(), templateBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        maskCanvas = new Canvas(maskBitmap);
        if (initialMask != null && !initialMask.isRecycled()) {
            copyInitialMask(initialMask);
        }
        srcRect.set(0, 0, templateBitmap.getWidth(), templateBitmap.getHeight());
        invalidate();
    }

    private void copyInitialMask(Bitmap initialMask) {
        int width = Math.min(maskBitmap.getWidth(), initialMask.getWidth());
        int height = Math.min(maskBitmap.getHeight(), initialMask.getHeight());
        int[] pixels = new int[width * height];
        initialMask.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int v = Math.max(Color.red(p), Math.max(Color.green(p), Color.blue(p)));
            pixels[i] = v > 0 ? MASK_LAYER_COLOR : 0x00000000;
        }
        maskBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    public void setEraseMode(boolean eraseMode) {
        this.eraseMode = eraseMode;
    }

    public boolean isEraseMode() {
        return eraseMode;
    }

    public void setBrushSize(int brushSize) {
        this.brushSize = Math.max(1, Math.min(96, brushSize));
    }

    public int getBrushSize() {
        return brushSize;
    }

    public void clearMask() {
        if (maskCanvas != null) {
            maskCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            invalidate();
        }
    }

    public Bitmap exportMaskBitmap() {
        if (maskBitmap == null || maskBitmap.isRecycled()) {
            return null;
        }
        int width = maskBitmap.getWidth();
        int height = maskBitmap.getHeight();
        int[] pixels = new int[width * height];
        maskBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = Color.alpha(pixels[i]) > 0 ? Color.WHITE : Color.BLACK;
        }
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, width, 0, 0, width, height);
        return out;
    }

    public boolean hasMaskPixels() {
        if (maskBitmap == null || maskBitmap.isRecycled()) {
            return false;
        }
        int width = maskBitmap.getWidth();
        int height = maskBitmap.getHeight();
        int[] pixels = new int[width * height];
        maskBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pixel : pixels) {
            if (Color.alpha(pixel) > 0) {
                return true;
            }
        }
        return false;
    }

    public void release() {
        if (templateBitmap != null && !templateBitmap.isRecycled()) {
            templateBitmap.recycle();
        }
        if (maskBitmap != null && !maskBitmap.isRecycled()) {
            maskBitmap.recycle();
        }
        templateBitmap = null;
        maskBitmap = null;
        maskCanvas = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (templateBitmap == null || maskBitmap == null) {
            return;
        }
        updateDstRect();
        canvas.drawBitmap(templateBitmap, srcRect, dstRect, bitmapPaint);
        canvas.drawBitmap(maskBitmap, srcRect, dstRect, bitmapPaint);
        canvas.drawRect(dstRect, framePaint);
    }

    private void updateDstRect() {
        if (templateBitmap == null) {
            dstRect.setEmpty();
            return;
        }
        float viewW = getWidth() - getPaddingLeft() - getPaddingRight();
        float viewH = getHeight() - getPaddingTop() - getPaddingBottom();
        float srcW = templateBitmap.getWidth();
        float srcH = templateBitmap.getHeight();
        if (viewW <= 0 || viewH <= 0 || srcW <= 0 || srcH <= 0) {
            dstRect.setEmpty();
            return;
        }
        float scale = Math.min(viewW / srcW, viewH / srcH);
        float w = srcW * scale;
        float h = srcH * scale;
        float left = getPaddingLeft() + (viewW - w) / 2f;
        float top = getPaddingTop() + (viewH - h) / 2f;
        dstRect.set(left, top, left + w, top + h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (maskCanvas == null || templateBitmap == null) {
            return false;
        }
        updateDstRect();
        float[] point = toBitmapPoint(event.getX(), event.getY());
        if (point == null) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                lastX = -1f;
                lastY = -1f;
            }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drawAt(point[0], point[1]);
                lastX = point[0];
                lastY = point[1];
                return true;
            case MotionEvent.ACTION_MOVE:
                if (lastX < 0 || lastY < 0) {
                    drawAt(point[0], point[1]);
                } else {
                    drawLine(lastX, lastY, point[0], point[1]);
                }
                lastX = point[0];
                lastY = point[1];
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastX = -1f;
                lastY = -1f;
                return true;
            default:
                return true;
        }
    }

    @Nullable
    private float[] toBitmapPoint(float x, float y) {
        if (dstRect.isEmpty() || !dstRect.contains(x, y)) {
            return null;
        }
        float bx = (x - dstRect.left) * templateBitmap.getWidth() / dstRect.width();
        float by = (y - dstRect.top) * templateBitmap.getHeight() / dstRect.height();
        bx = Math.max(0, Math.min(templateBitmap.getWidth() - 1, bx));
        by = Math.max(0, Math.min(templateBitmap.getHeight() - 1, by));
        return new float[]{bx, by};
    }

    private void drawLine(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dy) / Math.max(1, brushSize / 2f)));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            drawAt(x1 + dx * t, y1 + dy * t);
        }
    }

    private void drawAt(float x, float y) {
        if (maskCanvas == null) {
            return;
        }
        if (eraseMode) {
            maskPaint.setColor(Color.TRANSPARENT);
            maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        } else {
            maskPaint.setColor(MASK_LAYER_COLOR);
            maskPaint.setXfermode(null);
        }
        maskCanvas.drawCircle(x, y, Math.max(0.5f, brushSize / 2f), maskPaint);
        maskPaint.setXfermode(null);
        invalidate();
    }
}

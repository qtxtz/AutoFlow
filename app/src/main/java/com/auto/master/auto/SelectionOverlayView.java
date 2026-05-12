package com.auto.master.auto;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.*;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.auto.master.R;

public class SelectionOverlayView extends FrameLayout {

    // ===================== 兼容旧接口（你旧代码里的 setOnRegionSelectedListener） =====================
    public interface OnRegionSelectedListener {
        void onSelected(Rect rect, Bitmap bitmap);
    }
    private OnRegionSelectedListener oldListener;

    /** 兼容旧代码：框选结束立刻回调（这里改为点击“确定”才回调旧接口，以更符合 Windows 风格） */
    public void setOnRegionSelectedListener(OnRegionSelectedListener l) {
        this.oldListener = l;
    }

    // ===================== 新接口（确认/取消） =====================
    public interface Listener {
        void onConfirm(Rect rectInOverlay, Bitmap croppedBitmap);

        void onCancel();
    }
    public interface OnRefineRequestedListener {
        void onRefineRequested(Rect rectInOverlay);
    }
    private Listener listener;
    private OnRefineRequestedListener refineRequestedListener;
    private boolean refineEnabled = false;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setOnRefineRequestedListener(@Nullable OnRefineRequestedListener listener) {
        this.refineRequestedListener = listener;
    }

    public void setRefineEnabled(boolean enabled) {
        this.refineEnabled = enabled;
        if (btnRefine != null) {
            btnRefine.setVisibility(enabled ? VISIBLE : GONE);
        }
    }

    // ===================== 绘制相关 =====================
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect selection = new Rect();
    private boolean hasSelection = false;

    // buttons
    private LinearLayout actionBar;
    private LinearLayout actionContentRow;
    private LinearLayout actionButtonColumn;
    private LinearLayout moreMenuPanel;
    private FrameLayout previewCard;
    private ImageView previewImage;
    private TextView previewBadge;
    private Button btnOk;
    private Button btnMore;
    private Button btnReset;
    private Button btnFull;
    private Button btnRefine;
    private Button btnCancel;
    private Bitmap previewBitmap;
    private boolean moreMenuExpanded = false;

    // magnifier (Android 9+)
    private android.widget.Magnifier magnifier;

    // interaction
    private enum Mode { NONE, CREATE, MOVE, RESIZE_LT, RESIZE_RT, RESIZE_LB, RESIZE_RB, RESIZE_L, RESIZE_R, RESIZE_T, RESIZE_B }
    private Mode mode = Mode.NONE;

    private float downX, downY;
    private final Rect startRect = new Rect();

    // sizes
    private int handleRadius;
    private int handleTouchSlop;
    private int minSize;
    private int borderWidth;
    private int precisionMagnifierThreshold;

    private final OverlayCanvasView canvasView;

    private Bitmap frozenBackground;   // 新增
    private boolean ownsFrozenBackground;

    public SelectionOverlayView(Context context) {
        this(context, null);
    }

    private long lastMoveUiTs = 0L;
    private long lastMagTs = 0L;
    // 或通过 setter
    public void setFrozenBackground(Bitmap bmp) {
        setFrozenBackground(bmp, true);
    }

    public void setFrozenBackground(Bitmap bmp, boolean ownsBitmap) {
        releaseFrozenBackground();
        this.frozenBackground = bmp;
        this.ownsFrozenBackground = ownsBitmap;
        updateSelectionPreview();
        canvasView.invalidate();
    }

    public SelectionOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        setWillNotDraw(false);
        setClickable(true);
        setFocusable(false);

        handleRadius = dp(6);
        handleTouchSlop = dp(18);
        //那个框的大小限制
        minSize = dp(10);
        borderWidth = dp(2);
        precisionMagnifierThreshold = dp(96);

        dimPaint.setColor(0x88000000);
        dimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidth);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);

        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        // 画布层
        canvasView = new OverlayCanvasView(context);
        addView(canvasView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 预览式工具条
        actionBar = new LinearLayout(context);
        actionBar.setOrientation(LinearLayout.VERTICAL);
        actionBar.setPadding(dp(10), dp(10), dp(10), dp(10));
        actionBar.setBackground(makeToolbarBackground());
        actionBar.setVisibility(GONE);
        actionBar.setClickable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            actionBar.setElevation(dp(8));
        }

        actionContentRow = new LinearLayout(context);
        actionContentRow.setOrientation(LinearLayout.HORIZONTAL);
        actionContentRow.setGravity(Gravity.CENTER_VERTICAL);

        previewCard = new FrameLayout(context);
        previewCard.setBackground(makePreviewCardBackground());
        previewCard.setClipToOutline(true);

        previewImage = new ImageView(context);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewImage.setBackgroundColor(0xFFEEF3F8);
        previewCard.addView(previewImage, new FrameLayout.LayoutParams(dp(92), dp(64)));

        previewBadge = new TextView(context);
        previewBadge.setTextColor(Color.WHITE);
        previewBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        previewBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        previewBadge.setBackground(makeRoundBg(0xB3121A24, dp(10)));
        previewBadge.setText("未选区");
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        badgeLp.gravity = Gravity.END | Gravity.BOTTOM;
        badgeLp.rightMargin = dp(6);
        badgeLp.bottomMargin = dp(6);
        previewCard.addView(previewBadge, badgeLp);

        actionButtonColumn = new LinearLayout(context);
        actionButtonColumn.setOrientation(LinearLayout.VERTICAL);

        btnOk = new Button(context);
        btnOk.setText("确认");
        btnMore = new Button(context);
        btnMore.setText("更多");
        btnReset = new Button(context);
        btnReset.setText("重选");
        btnFull = new Button(context);
        btnFull.setText("全屏");
        btnRefine = new Button(context);
        btnRefine.setText("精选");
        btnCancel = new Button(context);
        btnCancel.setText("取消");

        styleCaptureActionButton(btnOk, true, R.drawable.ic_overlay_check);
        styleCaptureActionButton(btnMore, false, R.drawable.ic_more_vert);
        styleSecondaryMenuButton(btnReset);
        styleSecondaryMenuButton(btnFull);
        styleSecondaryMenuButton(btnRefine);
        styleSecondaryMenuButton(btnCancel);

        actionButtonColumn.addView(btnOk, new LinearLayout.LayoutParams(dp(96), dp(40)));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dp(96), dp(36));
        moreLp.topMargin = dp(8);
        actionButtonColumn.addView(btnMore, moreLp);

        actionContentRow.addView(previewCard, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams actionColumnLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        actionColumnLp.leftMargin = dp(10);
        actionContentRow.addView(actionButtonColumn, actionColumnLp);

        moreMenuPanel = new LinearLayout(context);
        moreMenuPanel.setOrientation(LinearLayout.VERTICAL);
        moreMenuPanel.setVisibility(GONE);
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        menuLp.topMargin = dp(10);
        moreMenuPanel.addView(btnReset, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)));
        LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36));
        fullLp.topMargin = dp(6);
        moreMenuPanel.addView(btnFull, fullLp);
        LinearLayout.LayoutParams refineLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36));
        refineLp.topMargin = dp(6);
        moreMenuPanel.addView(btnRefine, refineLp);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36));
        cancelLp.topMargin = dp(6);
        moreMenuPanel.addView(btnCancel, cancelLp);
        btnRefine.setVisibility(refineEnabled ? VISIBLE : GONE);

        actionBar.addView(actionContentRow, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        ));
        actionBar.addView(moreMenuPanel, menuLp);

        LayoutParams actionLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        addView(actionBar, actionLp);

        btnOk.setOnClickListener(v -> {
            if (!hasSelection) return;
            hideMagnifier();
            Rect normalized = new Rect(selection);
            normalize(normalized);

            // 动态背景：不在 View 内截图，交给外部在后台截全屏再裁剪
            if (listener != null) listener.onConfirm(normalized, null);
            if (oldListener != null) oldListener.onSelected(normalized, null);
        });
        btnMore.setOnClickListener(v -> toggleMoreMenu());
        btnReset.setOnClickListener(v -> resetSelection());
        btnFull.setOnClickListener(v -> selectFullScreen());
        btnRefine.setOnClickListener(v -> requestRefine());
        btnCancel.setOnClickListener(v -> cancelSelection());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            magnifier = new android.widget.Magnifier(canvasView);
        }

        // DO NOT call setFitsSystemWindows(true) here.
        // The overlay window uses FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS so it covers
        // the full physical screen including the status bar.  If fitsSystemWindows is true the
        // FrameLayout adds top padding equal to the status bar height, which shifts canvasView
        // down by topInsetPx pixels.  Touch coordinates inside canvasView are then
        //   screen_y - topInsetPx
        // instead of screen_y, causing a constant ~80px upward offset in the saved template crop.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        hideMagnifier();
        releaseFrozenBackground();
        super.onDetachedFromWindow();
    }

    private void releaseFrozenBackground() {
        if (ownsFrozenBackground && frozenBackground != null && !frozenBackground.isRecycled()) {
            frozenBackground.recycle();
        }
        releasePreviewBitmap();
        frozenBackground = null;
        ownsFrozenBackground = false;
    }

    // ===================== 内部画布 View =====================
    private class OverlayCanvasView extends View {

        private int screenWidth = 0;
        private int screenHeight = 0;


        private boolean frameScheduled = false;
        private float pendingX, pendingY;
        private boolean actionBarHidden = false;
        private void hideActionBarIfNeeded() {
            if (!actionBarHidden) {
                actionBar.setVisibility(GONE);
                hideMoreMenu();
                actionBarHidden = true;
            }
        }

        private void scheduleFrameUpdate() {
            if (frameScheduled) return;
            frameScheduled = true;
            postOnAnimation(this::runFrameUpdate);
        }

        private void runFrameUpdate() {
            frameScheduled = false;

            if (mode == Mode.NONE) {
                postInvalidateOnAnimation();
                return;
            }

            final float x = pendingX;
            final float y = pendingY;

            final float dx = x - downX;
            final float dy = y - downY;

            if (mode == Mode.CREATE) {
                selection.left = (int) Math.min(downX, x);
                selection.top = (int) Math.min(downY, y);
                selection.right = (int) Math.max(downX, x);
                selection.bottom = (int) Math.max(downY, y);

            } else if (mode == Mode.MOVE) {
                // 必须基于 startRect
                selection.set(startRect);
                selection.offset((int) dx, (int) dy);

            } else {
                selection.set(startRect);
                resizeByMode(selection, mode, x, y);
            }

            // MOVE 时做轻量 clamp，确保不飞出屏幕
            clampToBounds(selection);

            postInvalidateOnAnimation();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            screenWidth = w;
            screenHeight = h;
        }
        public OverlayCanvasView(Context context) { super(context); }

        // 记录最后一次有效重绘的时间
        private long lastInvalidateTime = 0;
        private static final long MIN_INVALIDATE_INTERVAL_MS = 33;  // ≈30fps，横屏最稳
        // 如果想更丝滑可改 16（60fps），但容易掉帧；卡就改 50（20fps）

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            final int w = getWidth();
            final int h = getHeight();

            if (frozenBackground != null && !frozenBackground.isRecycled()) {
                canvas.drawBitmap(frozenBackground, null, new Rect(0, 0, w, h), null);
            }

            if (!hasSelection || selection.isEmpty() || selection.width() <= 0 || selection.height() <= 0) {
                // 没选区：整屏遮罩（底下动态透出）
                canvas.drawRect(0, 0, w, h, dimPaint);
                return;
            }

            int l = selection.left;
            int t = selection.top;
            int r = selection.right;
            int b = selection.bottom;

            // 四边遮罩（极快）
            canvas.drawRect(0, 0, w, t, dimPaint);   // 上
            canvas.drawRect(0, b, w, h, dimPaint);   // 下
            canvas.drawRect(0, t, l, b, dimPaint);   // 左
            canvas.drawRect(r, t, w, b, dimPaint);   // 右

            canvas.drawRect(selection, borderPaint);
        }

        private void maybeUpdateMagnifier(long now, float x, float y) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || magnifier == null) {
                return;
            }
            if (now - lastMagTs < 16L) {
                return;
            }
            lastMagTs = now;

            if (!shouldShowPrecisionMagnifier()) {
                hideMagnifier();
                return;
            }

            float clampedX = Math.max(0f, Math.min(x, getWidth()));
            float clampedY = Math.max(0f, Math.min(y, getHeight()));
            showMagnifier(clampedX, clampedY);
        }

        private boolean shouldShowPrecisionMagnifier() {
            if (mode == Mode.NONE) {
                return false;
            }
            if (!hasSelection || mode == Mode.CREATE) {
                return true;
            }
            if (mode != Mode.MOVE) {
                return selection.width() <= precisionMagnifierThreshold
                        || selection.height() <= precisionMagnifierThreshold;
            }
            return selection.width() <= precisionMagnifierThreshold / 2
                    || selection.height() <= precisionMagnifierThreshold / 2;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            final float x = event.getX();
            final float y = event.getY();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = x;
                    downY = y;
                    startRect.set(selection);

                    if (!hasSelection) {
                        hasSelection = true;
                        mode = Mode.CREATE;
                        selection.set((int) x, (int) y, (int) x, (int) y);
                        hideActionBarIfNeeded();
                    } else {
                        mode = hitTest(x, y);
                        if (mode == Mode.NONE) {
                            mode = Mode.CREATE;
                            selection.set((int) x, (int) y, (int) x, (int) y);
                            hideActionBarIfNeeded();
                        }
                    }

                    // 记录最新点，并安排一帧刷新
                    pendingX = x;
                    pendingY = y;
                    maybeUpdateMagnifier(SystemClock.uptimeMillis(), x, y);
                    scheduleFrameUpdate();
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (mode == Mode.NONE) return true;

                    // MOVE 不做任何重活：只记录最新坐标 + 确保帧回调已安排
                    pendingX = x;
                    pendingY = y;
                    hideActionBarIfNeeded();
                    maybeUpdateMagnifier(SystemClock.uptimeMillis(), x, y);
                    scheduleFrameUpdate();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    // 结束时，强制用“最后位置”做一次最终更新
                    pendingX = x;
                    pendingY = y;
                    runFrameUpdate(); // 立即更新一次（不等下一帧）
                    hideMagnifier();

                    // 收尾：完整修正
                    if (hasSelection) {
                        normalize(selection);
                        clampToBounds(selection);

                        if (mode == Mode.CREATE) {
                            ensureMinSize(selection, (int) downX, (int) downY);
                        } else {
                            ensureMinSize(selection, mode, startRect);
                        }

                        if (selection.width() < minSize || selection.height() < minSize) {
                            hasSelection = false;
                            actionBar.setVisibility(GONE);
                            hideMoreMenu();
                            actionBarHidden = true;
                            releasePreviewBitmap();
                            postInvalidateOnAnimation();
                            mode = Mode.NONE;
                            return true;
                        }

                        updateSelectionPreview();
                        positionActionBarNearSelection();
                        actionBar.setVisibility(VISIBLE);
                        actionBarHidden = false;
                        postInvalidateOnAnimation();
                    }

                    mode = Mode.NONE;
                    return true;
                }
            }

            return super.onTouchEvent(event);
        }

    }

    // ===================== hit test =====================
    private Mode hitTest(float x, float y) {
        if (!hasSelection) return Mode.NONE;

        if (near(x, y, selection.left, selection.top)) return Mode.RESIZE_LT;
        if (near(x, y, selection.right, selection.top)) return Mode.RESIZE_RT;
        if (near(x, y, selection.left, selection.bottom)) return Mode.RESIZE_LB;
        if (near(x, y, selection.right, selection.bottom)) return Mode.RESIZE_RB;

        int cx = (selection.left + selection.right) / 2;
        int cy = (selection.top + selection.bottom) / 2;

        if (near(x, y, selection.left, cy)) return Mode.RESIZE_L;
        if (near(x, y, selection.right, cy)) return Mode.RESIZE_R;
        if (near(x, y, cx, selection.top)) return Mode.RESIZE_T;
        if (near(x, y, cx, selection.bottom)) return Mode.RESIZE_B;

        if (selection.contains((int)x, (int)y)) return Mode.MOVE;

        return Mode.NONE;
    }

    private boolean near(float x, float y, int px, int py) {
        return Math.abs(x - px) <= handleTouchSlop && Math.abs(y - py) <= handleTouchSlop;
    }

    private void drawHandles(Canvas canvas) {
        int l = selection.left, t = selection.top, r = selection.right, b = selection.bottom;
        int cx = (l + r) / 2, cy = (t + b) / 2;

        canvas.drawCircle(l, t, handleRadius, handlePaint);
        canvas.drawCircle(r, t, handleRadius, handlePaint);
        canvas.drawCircle(l, b, handleRadius, handlePaint);
        canvas.drawCircle(r, b, handleRadius, handlePaint);

        canvas.drawCircle(l, cy, handleRadius, handlePaint);
        canvas.drawCircle(r, cy, handleRadius, handlePaint);
        canvas.drawCircle(cx, t, handleRadius, handlePaint);
        canvas.drawCircle(cx, b, handleRadius, handlePaint);
    }

    // ===================== geometry helpers =====================
    private void normalize(Rect r) {
        int l = Math.min(r.left, r.right);
        int rr = Math.max(r.left, r.right);
        int t = Math.min(r.top, r.bottom);
        int bb = Math.max(r.top, r.bottom);
        r.set(l, t, rr, bb);
    }

    private void clampToBounds(Rect r) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        r.left = clamp(r.left, 0, w);
        r.right = clamp(r.right, 0, w);
        r.top = clamp(r.top, 0, h);
        r.bottom = clamp(r.bottom, 0, h);
        normalize(r);
    }

    private void clampMove(Rect r) {
        int w = getWidth();
        int h = getHeight();
        int rw = r.width();
        int rh = r.height();

        int left = r.left, top = r.top;

        if (left < 0) left = 0;
        if (top < 0) top = 0;
        if (left + rw > w) left = w - rw;
        if (top + rh > h) top = h - rh;

        r.set(left, top, left + rw, top + rh);
    }

    private void ensureMinSize(Rect r, int anchorX, int anchorY) {
        normalize(r);
        if (r.width() < minSize) {
            if (anchorX <= r.left) r.right = r.left + minSize;
            else r.left = r.right - minSize;
        }
        if (r.height() < minSize) {
            if (anchorY <= r.top) r.bottom = r.top + minSize;
            else r.top = r.bottom - minSize;
        }
        normalize(r);
        clampToBounds(r);
    }

    private void ensureMinSize(Rect r, Mode m, Rect start) {
        normalize(r);
        if (r.width() < minSize) {
            switch (m) {
                case RESIZE_L:
                case RESIZE_LT:
                case RESIZE_LB:
                    r.left = r.right - minSize; break;
                case RESIZE_R:
                case RESIZE_RT:
                case RESIZE_RB:
                    r.right = r.left + minSize; break;
            }
        }
        if (r.height() < minSize) {
            switch (m) {
                case RESIZE_T:
                case RESIZE_LT:
                case RESIZE_RT:
                    r.top = r.bottom - minSize; break;
                case RESIZE_B:
                case RESIZE_LB:
                case RESIZE_RB:
                    r.bottom = r.top + minSize; break;
            }
        }
        normalize(r);
        clampToBounds(r);
    }

    private void resizeByMode(Rect r, Mode m, float x, float y) {
        switch (m) {
            case RESIZE_LT: r.left = (int) x; r.top = (int) y; break;
            case RESIZE_RT: r.right = (int) x; r.top = (int) y; break;
            case RESIZE_LB: r.left = (int) x; r.bottom = (int) y; break;
            case RESIZE_RB: r.right = (int) x; r.bottom = (int) y; break;

            case RESIZE_L:  r.left = (int) x; break;
            case RESIZE_R:  r.right = (int) x; break;
            case RESIZE_T:  r.top = (int) y; break;
            case RESIZE_B:  r.bottom = (int) y; break;
        }
    }

    // ===================== action bar position =====================
    private void positionActionBarNearSelection() {
        actionBar.measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));

        int barW = actionBar.getMeasuredWidth();
        int barH = actionBar.getMeasuredHeight();
        int margin = dp(8);

        int x = selection.right - barW;
        int y = selection.bottom + margin;

        if (y + barH > getHeight()) {
            y = selection.top - barH - margin;
        }
        if (y < 0) {
            y = selection.top + margin;
        }

        if (y + barH > getHeight() - margin) {
            y = Math.max(margin, getHeight() - margin - barH);
        }
        if (x < margin) x = margin;
        if (x + barW > getWidth() - margin) x = getWidth() - margin - barW;
        if (x < margin) x = margin;

        LayoutParams lp = (LayoutParams) actionBar.getLayoutParams();
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.gravity = Gravity.TOP | Gravity.START;
        actionBar.setLayoutParams(lp);
    }

    private void styleCaptureActionButton(Button button, boolean primary, int iconRes) {
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 13 : 12);
        button.setTextColor(primary ? Color.WHITE : 0xFFF1F5F9);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? 0xFF2563EB : 0xFF263445));
        button.setBackground(makeRoundBg(primary ? 0xFF2563EB : 0xFF263445, dp(12)));
        button.setPadding(dp(12), 0, dp(12), 0);
        Drawable icon = ContextCompat.getDrawable(getContext(), iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(primary ? Color.WHITE : 0xFFF1F5F9);
            icon.setBounds(0, 0, dp(16), dp(16));
            button.setCompoundDrawablesRelative(icon, null, null, null);
            button.setCompoundDrawablePadding(dp(6));
        }
    }

    private void styleSecondaryMenuButton(Button button) {
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTextColor(0xFFF8FAFC);
        button.setBackground(makeRoundBg(0xFF223041, dp(10)));
        button.setPadding(dp(12), 0, dp(12), 0);
    }

    private void toggleMoreMenu() {
        moreMenuExpanded = !moreMenuExpanded;
        moreMenuPanel.setVisibility(moreMenuExpanded ? VISIBLE : GONE);
        btnMore.setText(moreMenuExpanded ? "收起" : "更多");
        positionActionBarNearSelection();
    }

    private void hideMoreMenu() {
        moreMenuExpanded = false;
        if (moreMenuPanel != null) {
            moreMenuPanel.setVisibility(GONE);
        }
        if (btnMore != null) {
            btnMore.setText("更多");
        }
    }

    private void resetSelection() {
        hasSelection = false;
        selection.setEmpty();
        actionBar.setVisibility(GONE);
        hideMoreMenu();
        hideMagnifier();
        releasePreviewBitmap();
        canvasView.invalidate();
    }

    private void selectFullScreen() {
        selection.set(0, 0, getWidth(), getHeight());
        hasSelection = true;
        hideMoreMenu();
        updateSelectionPreview();
        positionActionBarNearSelection();
        actionBar.setVisibility(VISIBLE);
        hideMagnifier();
        canvasView.invalidate();
    }

    private void requestRefine() {
        if (!hasSelection || !refineEnabled || refineRequestedListener == null) {
            return;
        }
        hideMoreMenu();
        hideMagnifier();
        Rect normalized = new Rect(selection);
        normalize(normalized);
        refineRequestedListener.onRefineRequested(normalized);
    }

    private void cancelSelection() {
        hideMoreMenu();
        hideMagnifier();
        if (listener != null) {
            listener.onCancel();
        }
    }

    private void updateSelectionPreview() {
        if (previewImage == null || previewBadge == null) {
            return;
        }
        if (!hasSelection || selection.isEmpty() || selection.width() <= 0 || selection.height() <= 0) {
            releasePreviewBitmap();
            previewImage.setImageDrawable(null);
            previewImage.setBackgroundColor(0xFFEEF3F8);
            previewBadge.setText("未选区");
            return;
        }

        releasePreviewBitmap();
        previewBitmap = captureAndCrop(new Rect(selection));
        if (previewBitmap != null) {
            previewImage.setImageBitmap(previewBitmap);
            previewImage.setBackgroundColor(Color.TRANSPARENT);
        } else {
            previewImage.setImageDrawable(null);
            previewImage.setBackgroundColor(0xFFEEF3F8);
        }
        previewBadge.setText(selection.width() + " × " + selection.height());
    }

    private void releasePreviewBitmap() {
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = null;
    }

    private Drawable makeToolbarBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE916202C);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0x4DFFFFFF);
        return background;
    }

    private Drawable makePreviewCardBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFFFF);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), 0x1F0F172A);
        return background;
    }

    // ===================== capture & crop =====================
    @Nullable
    private Bitmap captureAndCrop(Rect selRectInOverlay) {
        if (frozenBackground == null || frozenBackground.isRecycled()) {
            Log.w("SelectionOverlayView", "No frozen background, fallback to live capture");
            return null; // 或者返回 null
        }

        float scaleX = getWidth() > 0 ? (float) frozenBackground.getWidth() / (float) getWidth() : 1f;
        float scaleY = getHeight() > 0 ? (float) frozenBackground.getHeight() / (float) getHeight() : 1f;
        Rect crop = new Rect(
                Math.round(selRectInOverlay.left * scaleX),
                Math.round(selRectInOverlay.top * scaleY),
                Math.round(selRectInOverlay.right * scaleX),
                Math.round(selRectInOverlay.bottom * scaleY)
        );

        crop.left   = clamp(crop.left,   0, frozenBackground.getWidth());
        crop.right  = clamp(crop.right,  0, frozenBackground.getWidth());
        crop.top    = clamp(crop.top,    0, frozenBackground.getHeight());
        crop.bottom = clamp(crop.bottom, 0, frozenBackground.getHeight());
        normalize(crop);

        if (crop.width() <= 0 || crop.height() <= 0) return null;

        try {
            return Bitmap.createBitmap(frozenBackground,
                    crop.left, crop.top, crop.width(), crop.height());
        } catch (Exception e) {
            Log.e("SelectionOverlayView", "Crop failed from frozenBackground", e);
            return null;
        }
    }
    // ===================== magnifier =====================
    private void showMagnifier(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && magnifier != null) {
            magnifier.show(x, y);
        }
    }

    private void hideMagnifier() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && magnifier != null) {
            magnifier.dismiss();
        }
    }

    // ===================== utils =====================
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private Drawable makeRoundBg(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }
}

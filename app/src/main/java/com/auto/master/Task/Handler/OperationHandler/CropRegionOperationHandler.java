package com.auto.master.Task.Handler.OperationHandler;

import static com.auto.master.auto.ScriptRunner.normalizeRect;
import static com.auto.master.auto.ScriptRunner.safeRemove;
import static com.auto.master.auto.ScriptRunner.toastOnMain;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.auto.master.R;
import com.auto.master.Task.Operation.CropRegionOperation;
import com.auto.master.Task.Operation.DelayOperation;
import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OperationContext;
import com.auto.master.Template.Template;
import com.auto.master.auto.ActivityHolder;
import com.auto.master.auto.AutoAccessibilityService;
import com.auto.master.auto.ColorPointPickerView;
import com.auto.master.auto.SelectionOverlayView;
import com.auto.master.capture.CaptureScaleHelper;
import com.auto.master.capture.ScreenCapture;
import com.auto.master.capture.ScreenCaptureManager;
import com.auto.master.floatwin.TemplateMaskEditorView;
import com.auto.master.utils.OpenCVHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.opencv.core.Mat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 这是一个 click 的处理器
 * 它的作用就只是调用无障碍然后点击那个点
 */
public class CropRegionOperationHandler extends OperationHandler {

    private static final String TAG = "CropRegionOpHandler";
    private static final long TEMPLATE_CAPTURE_TIMEOUT_MS = 2200L;
    private static final long TEMPLATE_CAPTURE_INTERVAL_MS = 80L;
    public static Integer inited = 0;
    // 静态复用，Gson 线程安全，避免 saveManifestToFile/saveImg 每次 new 触发反射初始化
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    @Nullable
    private static volatile TemplateCaptureEventListener templateCaptureEventListener;

    public interface TemplateCaptureEventListener {
        void onTemplateSaved(String projectName, String taskName, String saveFileName, Rect rect);

        void onTemplateCaptureCancelled(String projectName, String taskName, String saveFileName);
    }

    public static void setTemplateCaptureEventListener(@Nullable TemplateCaptureEventListener listener) {
        templateCaptureEventListener = listener;
    }

    public static void clearTemplateCaptureEventListener(@Nullable TemplateCaptureEventListener expected) {
        if (expected == null || templateCaptureEventListener == expected) {
            templateCaptureEventListener = null;
        }
    }


//    和operation 对应
     CropRegionOperationHandler() {
         this.setType(3);
    }

    /**
     * 读取文件内容为字符串
     */
    public static String readFileToString(File file) {
        try {
            // 方法1：使用 Files (Java 7+)
//            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 方法2：使用 BufferedReader (兼容老版本)
        
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();

        } catch (IOException e) {
            Log.e(TAG, "读取文件失败: " + file.getAbsolutePath(), e);
            return "";
        }
    }

    /**
     * 保存 manifest 到 JSON 文件
     */
    private void saveManifestToFile(Map<String, List<Integer>> manifest, File outputFile) {
        BufferedWriter bufferedWriter = null;
        try {
            // 确保目录存在
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 使用 Gson 格式化输出（美化 JSON）
            String jsonContent = GSON.toJson(manifest);

            // 使用 BufferedWriter 写入文件
            bufferedWriter = new BufferedWriter(new FileWriter(outputFile));
            bufferedWriter.write(jsonContent);
            bufferedWriter.flush(); // 确保数据写入磁盘

            Log.d(TAG, "成功保存 manifest.json，共 " + manifest.size() + " 个条目");

        } catch (IOException e) {
            Log.e(TAG, "保存 manifest.json 失败", e);
            throw new RuntimeException("保存 manifest.json 失败", e);
        } finally {
            // 确保资源被释放
            if (bufferedWriter != null) {
                try {
                    bufferedWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭 BufferedWriter 失败", e);
                }
            }
        }
    }
    private void saveImg(Bitmap cropped, Context a, String projectName, String taskName,
                         String saveFileName, List<Integer> stdBbox) {
        saveImg(cropped, a, projectName, taskName, saveFileName, stdBbox, null);
    }

    private void saveImg(Bitmap cropped, Context a, String projectName, String taskName,
                         String saveFileName, List<Integer> stdBbox,
                         @Nullable String targetScaleDir) {
        saveImg(cropped, null, a, projectName, taskName, saveFileName, stdBbox, targetScaleDir);
    }

    private void saveImg(Bitmap cropped,
                         @Nullable Bitmap mask,
                         Context a,
                         String projectName,
                         String taskName,
                         String saveFileName,
                         List<Integer> stdBbox,
                         @Nullable String targetScaleDir) {
        File projectDir_ = new File(a.getExternalFilesDir(null),"projects");
        File projectDir = new File(projectDir_,projectName);
        File taskDir = new File(projectDir,taskName);
        File imgDir = new File(taskDir,"img");
        if (!imgDir.exists()){
            imgDir.mkdirs();
        }

        // scale-aware：优先使用调用方传入的 targetScaleDir（替换截图时保持与旧文件同目录），
        // 否则由当前 CAPTURE_SCALE 决定
        File scaleDir;
        if (!android.text.TextUtils.isEmpty(targetScaleDir)) {
            scaleDir = new File(imgDir, targetScaleDir);
            if (!scaleDir.exists()) scaleDir.mkdirs();
        } else {
            scaleDir = CaptureScaleHelper.getOrCreateScaleImgDir(imgDir, ScreenCaptureManager.CAPTURE_SCALE);
        }

        File croppedImgFile = new File(scaleDir, saveFileName);
        String maskFileName = CaptureScaleHelper.getTemplateMaskFileName(saveFileName);
        File maskFile = android.text.TextUtils.isEmpty(maskFileName) ? null : new File(scaleDir, maskFileName);

        // 替换旧文件前清除 BitmapManager 的缩略图缓存，确保 UI 刷新后显示新截图
        com.auto.master.utils.BitmapManager.getInstance().removeBitmap(croppedImgFile.getAbsolutePath());
        if (maskFile != null) {
            com.auto.master.utils.BitmapManager.getInstance().removeBitmap(maskFile.getAbsolutePath());
        }
        // 同时清除 Template mat 缓存，确保下次匹配使用新模板
        Template.clearTaskSingleMatCache(projectName, taskName, saveFileName);
        if (!android.text.TextUtils.isEmpty(maskFileName)) {
            Template.clearTaskSingleMatCache(projectName, taskName, maskFileName);
        }

        try (FileOutputStream fileOutputStream = new FileOutputStream(croppedImgFile, false)) {
            boolean success = cropped.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream);
            fileOutputStream.flush();
            if (success){
                Log.d(TAG, "保存模板成功: " + croppedImgFile.getAbsolutePath());
                toastOnMain("保存模板成功: " + croppedImgFile.getName());
            }
            if (maskFile != null) {
                if (mask != null && !mask.isRecycled()) {
                    try (FileOutputStream maskOutputStream = new FileOutputStream(maskFile, false)) {
                        mask.compress(Bitmap.CompressFormat.PNG, 100, maskOutputStream);
                        maskOutputStream.flush();
                        Log.d(TAG, "保存模板 mask 成功: " + maskFile.getAbsolutePath());
                    }
                } else if (maskFile.exists() && !maskFile.delete()) {
                    Log.w(TAG, "旧模板 mask 删除失败: " + maskFile.getAbsolutePath());
                }
            }
            Map<String,List<Integer>> manifest  = new HashMap<>();
            manifest.put(saveFileName,stdBbox);
            // manifest.json is stored per-scale in the same directory as the template image
            File manifestFile = new File(scaleDir, "manifest.json");
            if (manifestFile.exists()){
                String jsonContent = readFileToString(manifestFile);
                Type type = new TypeToken<Map<String, List<Integer>>>() {}.getType();
                Map<String, List<Integer>> existingManifest = GSON.fromJson(jsonContent, type);
                if (existingManifest != null) {
                    manifest.putAll(existingManifest);
                    manifest.put(saveFileName,stdBbox);
                    Log.d(TAG, "成功加载现有 manifest.json，包含 " + manifest.size() + " 个条目");
                }
            } else {
                Log.d(TAG, "manifest.json 不存在，将创建新文件");
            }
            saveManifestToFile(manifest, manifestFile);

        } catch (Exception e) {
            return;
        }
    }

    private interface TemplateBboxReadyCallback {
        void onReady(Rect finalRect);
    }

    private interface TemplateMaskReadyCallback {
        void onReady(@Nullable Bitmap maskBitmap);
    }

    private interface TemplateMaskRectReadyCallback {
        void onReady(@Nullable Rect templateLocalMaskRect);
    }

    private interface TemplateSaveFinishedCallback {
        void onFinished();
    }

    private static class TemplateMaskConfig {
        final boolean smartMask;
        @Nullable
        final Rect localMaskRect;
        @Nullable
        final Bitmap maskBitmap;

        TemplateMaskConfig(boolean smartMask, @Nullable Rect localMaskRect) {
            this(smartMask, localMaskRect, null);
        }

        TemplateMaskConfig(@Nullable Bitmap maskBitmap) {
            this(false, null, maskBitmap);
        }

        TemplateMaskConfig(boolean smartMask, @Nullable Rect localMaskRect, @Nullable Bitmap maskBitmap) {
            this.smartMask = smartMask;
            this.localMaskRect = localMaskRect == null ? null : new Rect(localMaskRect);
            this.maskBitmap = maskBitmap;
        }
    }

    private void notifyTemplateSaved(String projectName,
                                     String taskName,
                                     String saveFileName,
                                     Rect rect) {
        TemplateCaptureEventListener listener = templateCaptureEventListener;
        if (listener == null || rect == null) {
            return;
        }
        Rect savedRect = new Rect(rect);
        getMainHandler().post(() ->
                listener.onTemplateSaved(projectName, taskName, saveFileName, savedRect));
    }

    private void notifyTemplateCaptureCancelled(String projectName,
                                                String taskName,
                                                String saveFileName) {
        TemplateCaptureEventListener listener = templateCaptureEventListener;
        if (listener == null) {
            return;
        }
        getMainHandler().post(() ->
                listener.onTemplateCaptureCancelled(projectName, taskName, saveFileName));
    }

    private void promptTemplateSaveMode(Context context,
                                        Rect coarseRect,
                                        Runnable onDirectSave,
                                        Runnable onRefine,
                                        Runnable onCancel) {
        getMainHandler().post(() -> {
            android.view.ContextThemeWrapper themed =
                    new android.view.ContextThemeWrapper(context, R.style.Theme_AtomMaster);
            String message = "粗选区域: x=" + coarseRect.left
                    + ", y=" + coarseRect.top
                    + ", w=" + coarseRect.width()
                    + ", h=" + coarseRect.height();
            AlertDialog dialog = new AlertDialog.Builder(themed)
                    .setTitle("模板截图")
                    .setMessage(message)
                    .setNegativeButton("取消", (d, which) -> {
                        if (onCancel != null) {
                            onCancel.run();
                        }
                    })
                    .setNeutralButton("进入精选", (d, which) -> {
                        if (onRefine != null) {
                            onRefine.run();
                        }
                    })
                    .setPositiveButton("直接保存", (d, which) -> {
                        if (onDirectSave != null) {
                            onDirectSave.run();
                        }
                    })
                    .create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
            }
            dialog.show();
        });
    }

    private void showTemplateRefinePointPicker(Context context,
                                               WindowManager wm,
                                               Bitmap sourceBitmap,
                                               Rect coarseRect,
                                               TemplateBboxReadyCallback callback,
                                               @Nullable Runnable onCancel) {
        if (sourceBitmap == null || sourceBitmap.isRecycled() || coarseRect == null) {
            toastOnMain("精选失败：截图为空");
            return;
        }
        ScreenCaptureManager captureManager = ScreenCaptureManager.getInstance();
        Rect captureRect = captureManager.toCaptureRect(coarseRect);
        if (captureRect == null) {
            toastOnMain("粗选区域过小，无法精选");
            return;
        }
        int scaledLeft = Math.max(0, Math.min(captureRect.left, sourceBitmap.getWidth() - 1));
        int scaledTop = Math.max(0, Math.min(captureRect.top, sourceBitmap.getHeight() - 1));
        int scaledRight = Math.max(scaledLeft + 1, Math.min(captureRect.right, sourceBitmap.getWidth()));
        int scaledBottom = Math.max(scaledTop + 1, Math.min(captureRect.bottom, sourceBitmap.getHeight()));
        if (scaledRight - scaledLeft <= 1 || scaledBottom - scaledTop <= 1) {
            toastOnMain("粗选区域过小，无法精选");
            return;
        }
        final int refineLeft = scaledLeft;
        final int refineTop = scaledTop;

        Bitmap refineBitmap = Bitmap.createBitmap(sourceBitmap,
                scaledLeft, scaledTop, scaledRight - scaledLeft, scaledBottom - scaledTop);

        getMainHandler().post(() -> {
            View overlay = LayoutInflater.from(context).inflate(R.layout.dialog_refine_template_bbox_overlay, null);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                    PixelFormat.TRANSLUCENT
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            ColorPointPickerView pickerView = overlay.findViewById(R.id.template_refine_picker_view);
            View floatingPanel = overlay.findViewById(R.id.template_refine_floating_panel);
            TextView tvStep = overlay.findViewById(R.id.tv_template_refine_step);
            TextView tvCoord = overlay.findViewById(R.id.tv_template_refine_coord);
            ImageView ivPreview = overlay.findViewById(R.id.iv_template_refine_preview);
            TextView btnConfirm = overlay.findViewById(R.id.btn_template_refine_confirm);

            final int[] firstPoint = new int[2];
            final boolean[] firstSelected = {false};
            final Bitmap[] previewBitmapHolder = new Bitmap[1];
            final long[] lastPreviewTs = {0L};

            pickerView.setOnSelectionChangedListener((x, y, color) -> {
                if (tvCoord != null) {
                    int absCaptureX = refineLeft + x;
                    int absCaptureY = refineTop + y;
                    int absScreenX = captureManager.captureToScreenX(absCaptureX);
                    int absScreenY = captureManager.captureToScreenY(absCaptureY);
                    if (firstSelected[0]) {
                        int left = Math.min(firstPoint[0], x);
                        int top = Math.min(firstPoint[1], y);
                        int right = Math.max(firstPoint[0], x) + 1;
                        int bottom = Math.max(firstPoint[1], y) + 1;
                        Rect selectedScreenRect = captureManager.toScreenRect(new Rect(
                                refineLeft + left,
                                refineTop + top,
                                refineLeft + right,
                                refineTop + bottom));
                        if (selectedScreenRect != null) {
                            tvCoord.setText("x=" + selectedScreenRect.left
                                    + ", y=" + selectedScreenRect.top
                                    + "  w=" + selectedScreenRect.width()
                                    + ", h=" + selectedScreenRect.height());
                        } else {
                            tvCoord.setText("x=" + absScreenX + ", y=" + absScreenY);
                        }
                    } else {
                        tvCoord.setText("x=" + absScreenX + ", y=" + absScreenY);
                    }
                }
                if (firstSelected[0] && ivPreview != null) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastPreviewTs[0] >= 40L) {
                        lastPreviewTs[0] = now;
                        updateTemplateRefinePreview(ivPreview, refineBitmap,
                                firstPoint[0], firstPoint[1], x, y, previewBitmapHolder);
                    }
                }
            });
            pickerView.setOnMagnifierLayoutChangedListener(rect ->
                    updateFloatingPanelPosition(floatingPanel, rect));
            pickerView.setScreenshot(refineBitmap, true);
            pickerView.setSelection(0, 0);
            if (tvStep != null) {
                tvStep.setText("精选第1步：选择左上角");
            }
            if (btnConfirm != null) {
                btnConfirm.setText("下一步");
            }

            View btnCancel = overlay.findViewById(R.id.btn_template_refine_cancel);
            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> {
                    recyclePreviewBitmap(previewBitmapHolder);
                    pickerView.release();
                    try {
                        wm.removeView(overlay);
                    } catch (Exception ignored) {
                    }
                    toastOnMain("已取消精选");
                    if (onCancel != null) {
                        onCancel.run();
                    }
                });
            }

            if (btnConfirm != null) {
                btnConfirm.setOnClickListener(v -> {
                    if (!pickerView.hasSelection()) {
                        toastOnMain("请先选择一个点");
                        return;
                    }
                    if (!firstSelected[0]) {
                        firstPoint[0] = pickerView.getSelectedX();
                        firstPoint[1] = pickerView.getSelectedY();
                        firstSelected[0] = true;
                        pickerView.setSelection(refineBitmap.getWidth() - 1, refineBitmap.getHeight() - 1);
                        if (tvStep != null) {
                            tvStep.setText("精选第2步：选择右下角");
                        }
                        if (ivPreview != null) {
                            ivPreview.setVisibility(View.VISIBLE);
                            updateTemplateRefinePreview(ivPreview, refineBitmap,
                                    firstPoint[0], firstPoint[1],
                                    pickerView.getSelectedX(), pickerView.getSelectedY(),
                                    previewBitmapHolder);
                        }
                        btnConfirm.setText("完成精选");
                        return;
                    }

                    int secondX = pickerView.getSelectedX();
                    int secondY = pickerView.getSelectedY();
                    int left = Math.min(firstPoint[0], secondX);
                    int top = Math.min(firstPoint[1], secondY);
                    int right = Math.max(firstPoint[0], secondX) + 1;
                    int bottom = Math.max(firstPoint[1], secondY) + 1;
                    Rect finalRect = captureManager.toScreenRect(new Rect(
                            refineLeft + left,
                            refineTop + top,
                            refineLeft + right,
                            refineTop + bottom));
                    recyclePreviewBitmap(previewBitmapHolder);
                    pickerView.release();
                    try {
                        wm.removeView(overlay);
                    } catch (Exception ignored) {
                    }
                    if (callback != null && finalRect != null) {
                        callback.onReady(finalRect);
                    } else if (onCancel != null) {
                        onCancel.run();
                    }
                });
            }

            try {
                wm.addView(overlay, lp);
                toastOnMain("精选模式：请先选左上角，再选右下角");
            } catch (Throwable t) {
                recyclePreviewBitmap(previewBitmapHolder);
                pickerView.release();
                Log.e(TAG, "add refine overlay failed", t);
                if (onCancel != null) {
                    onCancel.run();
                } else {
                    recycleBitmap(sourceBitmap);
                }
                toastOnMain("创建精选界面失败：" + t.getMessage());
            }
        });
    }

    private void updateFloatingPanelPosition(View panel, RectF magnifierBounds) {
        if (panel == null || magnifierBounds == null) {
            return;
        }
        panel.post(() -> {
            if (!(panel.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                return;
            }
            View parent = (View) panel.getParent();
            if (parent == null) {
                return;
            }
            int parentWidth = parent.getWidth();
            int parentHeight = parent.getHeight();
            if (parentWidth <= 0 || parentHeight <= 0) {
                return;
            }
            if (panel.getWidth() <= 0 || panel.getHeight() <= 0) {
                panel.measure(
                        View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(parentHeight, View.MeasureSpec.AT_MOST)
                );
            }
            int panelWidth = Math.max(panel.getWidth(), panel.getMeasuredWidth());
            int panelHeight = Math.max(panel.getHeight(), panel.getMeasuredHeight());
            int margin = dp(12);
            int gap = dp(12);

            int left;
            int top;
            if (magnifierBounds.right + gap + panelWidth <= parentWidth - margin) {
                left = Math.round(magnifierBounds.right + gap);
                top = Math.round(magnifierBounds.centerY() - panelHeight / 2f);
            } else if (magnifierBounds.left - gap - panelWidth >= margin) {
                left = Math.round(magnifierBounds.left - gap - panelWidth);
                top = Math.round(magnifierBounds.centerY() - panelHeight / 2f);
            } else if (magnifierBounds.bottom + gap + panelHeight <= parentHeight - margin) {
                left = Math.round(magnifierBounds.centerX() - panelWidth / 2f);
                top = Math.round(magnifierBounds.bottom + gap);
            } else {
                left = Math.round(magnifierBounds.centerX() - panelWidth / 2f);
                top = Math.round(magnifierBounds.top - gap - panelHeight);
            }

            left = Math.max(margin, Math.min(left, parentWidth - panelWidth - margin));
            top = Math.max(margin, Math.min(top, parentHeight - panelHeight - margin));

            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) panel.getLayoutParams();
            layoutParams.gravity = Gravity.TOP | Gravity.START;
            layoutParams.leftMargin = left;
            layoutParams.topMargin = top;
            panel.setLayoutParams(layoutParams);
        });
    }

    private void recyclePreviewBitmap(Bitmap[] previewHolder) {
        if (previewHolder == null || previewHolder.length == 0) {
            return;
        }
        Bitmap preview = previewHolder[0];
        if (preview != null && !preview.isRecycled()) {
            preview.recycle();
        }
        previewHolder[0] = null;
    }

    private void recycleBitmap(@Nullable Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private void updateTemplateRefinePreview(ImageView previewView,
                                             Bitmap sourceBitmap,
                                             int firstX,
                                             int firstY,
                                             int secondX,
                                             int secondY,
                                             Bitmap[] previewHolder) {
        if (previewView == null || sourceBitmap == null || sourceBitmap.isRecycled()) {
            return;
        }
        int left = Math.max(0, Math.min(firstX, secondX));
        int top = Math.max(0, Math.min(firstY, secondY));
        int right = Math.min(sourceBitmap.getWidth(), Math.max(firstX, secondX) + 1);
        int bottom = Math.min(sourceBitmap.getHeight(), Math.max(firstY, secondY) + 1);
        if (right <= left || bottom <= top) {
            previewView.setVisibility(View.GONE);
            recyclePreviewBitmap(previewHolder);
            return;
        }

        int previewSize = dp(88);
        Bitmap previewBitmap = Bitmap.createBitmap(previewSize, previewSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(previewBitmap);
        canvas.drawColor(Color.WHITE);
        Rect src = new Rect(left, top, right, bottom);
        Rect dst = new Rect(0, 0, previewSize, previewSize);
        float srcRatio = src.width() / (float) src.height();
        float dstRatio = 1f;
        if (srcRatio > dstRatio) {
            int targetHeight = Math.max(1, Math.round(previewSize / srcRatio));
            int offset = (previewSize - targetHeight) / 2;
            dst.set(0, offset, previewSize, offset + targetHeight);
        } else {
            int targetWidth = Math.max(1, Math.round(previewSize * srcRatio));
            int offset = (previewSize - targetWidth) / 2;
            dst.set(offset, 0, offset + targetWidth, previewSize);
        }
        canvas.drawBitmap(sourceBitmap, src, dst, null);
        recyclePreviewBitmap(previewHolder);
        previewHolder[0] = previewBitmap;
        previewView.setImageBitmap(previewBitmap);
        previewView.setVisibility(View.VISIBLE);
    }

    private void promptTemplateMaskMode(Context context,
                                        WindowManager wm,
                                        Bitmap sourceBitmap,
                                        Rect finalRect,
                                        String projectName,
                                        String taskName,
                                        String saveFileName,
                                        @Nullable String targetScaleDir,
                                        @Nullable TemplateSaveFinishedCallback finishedCallback) {
        getMainHandler().post(() -> {
            android.view.ContextThemeWrapper themed =
                    new android.view.ContextThemeWrapper(context, R.style.Theme_AtomMaster);
            AlertDialog dialog = new AlertDialog.Builder(themed)
                    .setTitle("模板 Mask")
                    .setMessage("可以为这个模板额外保存一张 mask 图。编辑时红色半透明图层表示参与匹配的区域；旧模板没有 mask 时会继续按原方式匹配。")
                    .setNegativeButton("不使用", (d, which) ->
                            saveTemplateFromRect(sourceBitmap, finalRect, context, projectName, taskName,
                                    saveFileName, targetScaleDir, null, finishedCallback))
                    .setNeutralButton("绘制 Mask", (d, which) ->
                            showTemplateMaskPaintEditor(context, wm, sourceBitmap, finalRect,
                                    "绘制模板 Mask",
                                    maskBitmap -> saveTemplateFromRect(sourceBitmap, finalRect, context,
                                            projectName, taskName, saveFileName, targetScaleDir,
                                            maskBitmap == null ? null : new TemplateMaskConfig(maskBitmap),
                                            finishedCallback),
                                    () -> saveTemplateFromRect(sourceBitmap, finalRect, context,
                                            projectName, taskName, saveFileName, targetScaleDir,
                                            null, finishedCallback)))
                    .setPositiveButton("智能生成", (d, which) ->
                            saveTemplateFromRect(sourceBitmap, finalRect, context, projectName, taskName,
                                    saveFileName, targetScaleDir,
                                    new TemplateMaskConfig(true, null), finishedCallback))
                    .create();
            dialog.setOnCancelListener(d -> saveTemplateFromRect(sourceBitmap, finalRect, context,
                    projectName, taskName, saveFileName, targetScaleDir, null, finishedCallback));
            if (dialog.getWindow() != null) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
            }
            dialog.show();
        });
    }

    private void showTemplateMaskPaintEditor(Context context,
                                             WindowManager wm,
                                             Bitmap sourceBitmap,
                                             Rect finalRect,
                                             String title,
                                             TemplateMaskReadyCallback callback,
                                             @Nullable Runnable onCancel) {
        Bitmap templateBitmap = createTemplateBitmapFromRect(sourceBitmap, finalRect);
        if (templateBitmap == null) {
            toastOnMain("Mask 编辑失败：模板区域无效");
            if (onCancel != null) onCancel.run();
            return;
        }
        getMainHandler().post(() -> {
            View overlay = LayoutInflater.from(context).inflate(R.layout.dialog_template_mask_editor, null);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                    PixelFormat.TRANSLUCENT
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            TemplateMaskEditorView editor = overlay.findViewById(R.id.template_mask_editor_view);
            TextView tvTitle = overlay.findViewById(R.id.tv_mask_editor_title);
            TextView btnDraw = overlay.findViewById(R.id.btn_mask_draw);
            TextView btnErase = overlay.findViewById(R.id.btn_mask_erase);
            TextView btnClear = overlay.findViewById(R.id.btn_mask_clear);
            TextView btnSave = overlay.findViewById(R.id.btn_mask_save);
            TextView btnDec = overlay.findViewById(R.id.btn_mask_brush_dec);
            TextView btnInc = overlay.findViewById(R.id.btn_mask_brush_inc);
            TextView tvBrush = overlay.findViewById(R.id.tv_mask_brush_size);

            if (tvTitle != null) tvTitle.setText(title);
            editor.setTemplateBitmap(templateBitmap, null);
            recycleBitmap(templateBitmap);

            Runnable updateModeUi = () -> {
                boolean erase = editor.isEraseMode();
                btnDraw.setBackgroundResource(erase ? R.drawable.item_operation_compact_bg : R.drawable.panel_btn_primary_selector);
                btnDraw.setTextColor(erase ? Color.rgb(49, 93, 191) : Color.WHITE);
                btnErase.setBackgroundResource(erase ? R.drawable.panel_btn_primary_selector : R.drawable.item_operation_compact_bg);
                btnErase.setTextColor(erase ? Color.WHITE : Color.rgb(49, 93, 191));
            };
            Runnable updateBrushUi = () -> tvBrush.setText("画笔 " + editor.getBrushSize() + " px");

            btnDraw.setOnClickListener(v -> {
                editor.setEraseMode(false);
                updateModeUi.run();
            });
            btnErase.setOnClickListener(v -> {
                editor.setEraseMode(true);
                updateModeUi.run();
            });
            btnClear.setOnClickListener(v -> editor.clearMask());
            btnDec.setOnClickListener(v -> {
                editor.setBrushSize(editor.getBrushSize() - 1);
                updateBrushUi.run();
            });
            btnInc.setOnClickListener(v -> {
                editor.setBrushSize(editor.getBrushSize() + 1);
                updateBrushUi.run();
            });
            overlay.findViewById(R.id.btn_mask_editor_close).setOnClickListener(v -> {
                editor.release();
                try { wm.removeView(overlay); } catch (Exception ignored) {}
                toastOnMain("已取消 Mask，按无 Mask 保存");
                if (onCancel != null) onCancel.run();
            });
            btnSave.setOnClickListener(v -> {
                Bitmap maskBitmap = editor.hasMaskPixels() ? editor.exportMaskBitmap() : null;
                editor.release();
                try { wm.removeView(overlay); } catch (Exception ignored) {}
                if (callback != null) callback.onReady(maskBitmap);
            });

            updateModeUi.run();
            updateBrushUi.run();
            try {
                wm.addView(overlay, lp);
                toastOnMain("Mask 编辑：红色图层参与匹配，擦除区域忽略");
            } catch (Throwable t) {
                editor.release();
                Log.e(TAG, "add mask editor failed", t);
                toastOnMain("创建 Mask 编辑器失败：" + t.getMessage());
                if (onCancel != null) onCancel.run();
            }
        });
    }

    @Nullable
    private Bitmap createTemplateBitmapFromRect(Bitmap sourceBitmap, Rect rect) {
        if (sourceBitmap == null || sourceBitmap.isRecycled() || rect == null) {
            return null;
        }
        ScreenCaptureManager captureManager = ScreenCaptureManager.getInstance();
        Rect captureRect = captureManager.toCaptureRect(rect);
        if (captureRect == null || captureRect.isEmpty()) {
            return null;
        }
        int left = Math.max(0, Math.min(captureRect.left, sourceBitmap.getWidth() - 1));
        int top = Math.max(0, Math.min(captureRect.top, sourceBitmap.getHeight() - 1));
        int right = Math.max(left + 1, Math.min(captureRect.right, sourceBitmap.getWidth()));
        int bottom = Math.max(top + 1, Math.min(captureRect.bottom, sourceBitmap.getHeight()));
        if (right <= left || bottom <= top) {
            return null;
        }
        return Bitmap.createBitmap(sourceBitmap, left, top, right - left, bottom - top);
    }

    private void showTemplateMaskRectPicker(Context context,
                                            WindowManager wm,
                                            Bitmap sourceBitmap,
                                            Rect finalRect,
                                            TemplateMaskRectReadyCallback callback,
                                            @Nullable Runnable onCancel) {
        if (sourceBitmap == null || sourceBitmap.isRecycled() || finalRect == null) {
            toastOnMain("Mask 选择失败：截图为空");
            if (onCancel != null) onCancel.run();
            return;
        }
        ScreenCaptureManager captureManager = ScreenCaptureManager.getInstance();
        Rect captureRect = captureManager.toCaptureRect(finalRect);
        if (captureRect == null || captureRect.isEmpty()) {
            toastOnMain("Mask 选择失败：模板区域无效");
            if (onCancel != null) onCancel.run();
            return;
        }

        int left = Math.max(0, Math.min(captureRect.left, sourceBitmap.getWidth() - 1));
        int top = Math.max(0, Math.min(captureRect.top, sourceBitmap.getHeight() - 1));
        int right = Math.max(left + 1, Math.min(captureRect.right, sourceBitmap.getWidth()));
        int bottom = Math.max(top + 1, Math.min(captureRect.bottom, sourceBitmap.getHeight()));
        Bitmap templateBitmap = Bitmap.createBitmap(sourceBitmap, left, top, right - left, bottom - top);

        getMainHandler().post(() -> {
            View overlay = LayoutInflater.from(context).inflate(R.layout.dialog_refine_template_bbox_overlay, null);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                    PixelFormat.TRANSLUCENT
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            ColorPointPickerView pickerView = overlay.findViewById(R.id.template_refine_picker_view);
            View floatingPanel = overlay.findViewById(R.id.template_refine_floating_panel);
            TextView tvStep = overlay.findViewById(R.id.tv_template_refine_step);
            TextView tvCoord = overlay.findViewById(R.id.tv_template_refine_coord);
            ImageView ivPreview = overlay.findViewById(R.id.iv_template_refine_preview);
            TextView btnConfirm = overlay.findViewById(R.id.btn_template_refine_confirm);
            TextView btnCancel = overlay.findViewById(R.id.btn_template_refine_cancel);

            final int[] firstPoint = new int[2];
            final boolean[] firstSelected = {false};
            final Bitmap[] previewBitmapHolder = new Bitmap[1];
            final long[] lastPreviewTs = {0L};

            pickerView.setOnSelectionChangedListener((x, y, color) -> {
                if (tvCoord != null) {
                    if (firstSelected[0]) {
                        int ml = Math.min(firstPoint[0], x);
                        int mt = Math.min(firstPoint[1], y);
                        int mr = Math.max(firstPoint[0], x) + 1;
                        int mb = Math.max(firstPoint[1], y) + 1;
                        tvCoord.setText("mask x=" + ml + ", y=" + mt
                                + "  w=" + (mr - ml) + ", h=" + (mb - mt));
                    } else {
                        tvCoord.setText("mask x=" + x + ", y=" + y);
                    }
                }
                if (firstSelected[0] && ivPreview != null) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastPreviewTs[0] >= 40L) {
                        lastPreviewTs[0] = now;
                        updateTemplateRefinePreview(ivPreview, templateBitmap,
                                firstPoint[0], firstPoint[1], x, y, previewBitmapHolder);
                    }
                }
            });
            pickerView.setOnMagnifierLayoutChangedListener(rect ->
                    updateFloatingPanelPosition(floatingPanel, rect));
            pickerView.setScreenshot(templateBitmap, true);
            pickerView.setSelection(0, 0);
            if (tvStep != null) tvStep.setText("Mask 第1步：选择左上角");
            if (btnConfirm != null) btnConfirm.setText("下一步");
            if (btnCancel != null) {
                btnCancel.setOnClickListener(v -> {
                    recyclePreviewBitmap(previewBitmapHolder);
                    pickerView.release();
                    recycleBitmap(templateBitmap);
                    try { wm.removeView(overlay); } catch (Exception ignored) {}
                    toastOnMain("已取消 Mask，按无 Mask 保存");
                    if (onCancel != null) onCancel.run();
                });
            }
            if (btnConfirm != null) {
                btnConfirm.setOnClickListener(v -> {
                    if (!pickerView.hasSelection()) {
                        toastOnMain("请先选择一个点");
                        return;
                    }
                    if (!firstSelected[0]) {
                        firstPoint[0] = pickerView.getSelectedX();
                        firstPoint[1] = pickerView.getSelectedY();
                        firstSelected[0] = true;
                        pickerView.setSelection(templateBitmap.getWidth() - 1, templateBitmap.getHeight() - 1);
                        if (tvStep != null) tvStep.setText("Mask 第2步：选择右下角");
                        if (ivPreview != null) {
                            ivPreview.setVisibility(View.VISIBLE);
                            updateTemplateRefinePreview(ivPreview, templateBitmap,
                                    firstPoint[0], firstPoint[1],
                                    pickerView.getSelectedX(), pickerView.getSelectedY(),
                                    previewBitmapHolder);
                        }
                        btnConfirm.setText("完成 Mask");
                        return;
                    }
                    int secondX = pickerView.getSelectedX();
                    int secondY = pickerView.getSelectedY();
                    Rect maskRect = new Rect(
                            Math.min(firstPoint[0], secondX),
                            Math.min(firstPoint[1], secondY),
                            Math.max(firstPoint[0], secondX) + 1,
                            Math.max(firstPoint[1], secondY) + 1);
                    recyclePreviewBitmap(previewBitmapHolder);
                    pickerView.release();
                    recycleBitmap(templateBitmap);
                    try { wm.removeView(overlay); } catch (Exception ignored) {}
                    if (callback != null) callback.onReady(maskRect);
                });
            }

            try {
                wm.addView(overlay, lp);
                toastOnMain("Mask 模式：红色图层表示参与匹配区域");
            } catch (Throwable t) {
                recyclePreviewBitmap(previewBitmapHolder);
                pickerView.release();
                recycleBitmap(templateBitmap);
                Log.e(TAG, "add mask overlay failed", t);
                toastOnMain("创建 Mask 界面失败：" + t.getMessage());
                if (onCancel != null) onCancel.run();
            }
        });
    }

    private Bitmap createRectMask(int width, int height, Rect localMaskRect) {
        Bitmap mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mask);
        canvas.drawColor(Color.BLACK);
        Rect safe = new Rect(localMaskRect);
        safe.left = Math.max(0, Math.min(safe.left, width - 1));
        safe.top = Math.max(0, Math.min(safe.top, height - 1));
        safe.right = Math.max(safe.left + 1, Math.min(safe.right, width));
        safe.bottom = Math.max(safe.top + 1, Math.min(safe.bottom, height));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawRect(safe, paint);
        return mask;
    }

    private Bitmap createSmartMask(Bitmap templateBitmap) {
        if (templateBitmap == null || templateBitmap.isRecycled()) {
            return null;
        }
        int width = templateBitmap.getWidth();
        int height = templateBitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        int[] pixels = new int[width * height];
        templateBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int x = 0; x < width; x++) {
            int top = pixels[x];
            int bottom = pixels[(height - 1) * width + x];
            r += Color.red(top) + Color.red(bottom);
            g += Color.green(top) + Color.green(bottom);
            b += Color.blue(top) + Color.blue(bottom);
            count += 2;
        }
        for (int y = 1; y < height - 1; y++) {
            int left = pixels[y * width];
            int right = pixels[y * width + width - 1];
            r += Color.red(left) + Color.red(right);
            g += Color.green(left) + Color.green(right);
            b += Color.blue(left) + Color.blue(right);
            count += 2;
        }
        if (count <= 0) {
            return null;
        }
        int bgR = (int) (r / count);
        int bgG = (int) (g / count);
        int bgB = (int) (b / count);
        int threshold = 28;
        int whiteCount = 0;
        int[] maskPixels = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int diff = Math.max(
                    Math.abs(Color.red(p) - bgR),
                    Math.max(Math.abs(Color.green(p) - bgG), Math.abs(Color.blue(p) - bgB)));
            if (diff >= threshold) {
                maskPixels[i] = Color.WHITE;
                whiteCount++;
            } else {
                maskPixels[i] = Color.BLACK;
            }
        }
        if (whiteCount < Math.max(4, pixels.length / 200)) {
            return null;
        }
        Bitmap mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mask.setPixels(maskPixels, 0, width, 0, 0, width, height);
        return mask;
    }

    private int dp(int value) {
        Context ctx = AutoAccessibilityService.get();
        if (ctx == null) {
            return value;
        }
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void saveTemplateFromRect(Bitmap sourceBitmap,
                                      Rect rect,
                                      Context context,
                                      String projectName,
                                      String taskName,
                                      String saveFileName,
                                      @Nullable String targetScaleDir) {
        saveTemplateFromRect(sourceBitmap, rect, context, projectName, taskName,
                saveFileName, targetScaleDir, null, null);
    }

    private void saveTemplateFromRect(Bitmap sourceBitmap,
                                      Rect rect,
                                      Context context,
                                      String projectName,
                                      String taskName,
                                      String saveFileName,
                                      @Nullable String targetScaleDir,
                                      @Nullable TemplateMaskConfig maskConfig,
                                      @Nullable TemplateSaveFinishedCallback finishedCallback) {
        if (sourceBitmap == null || sourceBitmap.isRecycled() || rect == null) {
            toastOnMain("保存失败：模板区域无效");
            if (finishedCallback != null) finishedCallback.onFinished();
            return;
        }

        ScreenCaptureManager captureManager = ScreenCaptureManager.getInstance();
        Rect captureRect = captureManager.toCaptureRect(rect);
        if (captureRect == null) {
            toastOnMain("选区太小");
            if (finishedCallback != null) finishedCallback.onFinished();
            return;
        }
        int bitmapLeft = captureRect.left;
        int bitmapTop = captureRect.top;
        int bitmapRight = captureRect.right;
        int bitmapBottom = captureRect.bottom;

        // 夹紧到 bitmap 边界
        bitmapLeft   = Math.max(0, Math.min(bitmapLeft,   sourceBitmap.getWidth() - 1));
        bitmapTop    = Math.max(0, Math.min(bitmapTop,    sourceBitmap.getHeight() - 1));
        bitmapRight  = Math.max(bitmapLeft + 1, Math.min(bitmapRight,  sourceBitmap.getWidth()));
        bitmapBottom = Math.max(bitmapTop + 1,  Math.min(bitmapBottom, sourceBitmap.getHeight()));

        if (bitmapRight - bitmapLeft <= 1 || bitmapBottom - bitmapTop <= 1) {
            toastOnMain("选区太小");
            if (finishedCallback != null) finishedCallback.onFinished();
            return;
        }

        // manifest.json 中始终存储屏幕坐标（scale-neutral），
        // 运行时由 ScreenCaptureManager.sanitizeRoi 统一转换到 capture 坐标
        Rect safeScreenRect = new Rect(rect);

        Bitmap cropped = null;
        Bitmap mask = null;
        try {
            cropped = Bitmap.createBitmap(sourceBitmap,
                    bitmapLeft, bitmapTop,
                    bitmapRight - bitmapLeft, bitmapBottom - bitmapTop);
            if (maskConfig != null) {
                if (maskConfig.maskBitmap != null && !maskConfig.maskBitmap.isRecycled()) {
                    mask = maskConfig.maskBitmap;
                } else if (maskConfig.smartMask) {
                    mask = createSmartMask(cropped);
                    if (mask == null) {
                        toastOnMain("智能 Mask 为空，已按无 Mask 保存");
                    }
                } else if (maskConfig.localMaskRect != null) {
                    mask = createRectMask(cropped.getWidth(), cropped.getHeight(), maskConfig.localMaskRect);
                }
            }
            List<Integer> bbox = Arrays.asList(
                    safeScreenRect.left,
                    safeScreenRect.top,
                    safeScreenRect.width(),
                    safeScreenRect.height()
            );
            saveImg(cropped, mask, context, projectName, taskName, saveFileName, bbox, targetScaleDir);
            notifyTemplateSaved(projectName, taskName, saveFileName, safeScreenRect);
        } finally {
            recycleBitmap(mask);
            recycleBitmap(cropped);
            if (finishedCallback != null) {
                finishedCallback.onFinished();
            }
        }
    }


    @Override
    public boolean handle(MetaOperation obj, OperationContext ctx) {
        CropRegionOperation cropRegionOperation = (CropRegionOperation) obj;

        AutoAccessibilityService svc = AutoAccessibilityService.get();
        if (svc == null) return false;


        //这是这个operation定义的一些东西
        Map<String, Object> inputMap = cropRegionOperation.getInputMap();
        List<Double> bbox = new ArrayList<>();
        Object o = inputMap.get(MetaOperation.BBOX);
        if (o instanceof ArrayList){
            bbox = (ArrayList) o;
        }else {
            bbox = null;
        }
        Object project = inputMap.get(MetaOperation.PROJECT);

        String projectName;
        if (project instanceof String){
            projectName = (String) project;
        } else {
            projectName = "fallback";
        }

        Object task = inputMap.get(MetaOperation.TASK);

        String taskName;
        if (task instanceof String){
            taskName = (String) task;
        } else {
            taskName = "";
        }

        Object saveFile = inputMap.get(MetaOperation.SAVEFILENAME);

        String saveFileName;
        if (saveFile instanceof String){
            saveFileName = (String) saveFile;
        } else {
            saveFileName = "fallback.png";
        }

        // 替换截图时传入的目标 scale 子目录（如 "scale_100"），null 表示由当前 CAPTURE_SCALE 决定
        Object targetScaleDirObj = inputMap.get(MetaOperation.TARGET_SCALE_DIR);
        String targetScaleDir = targetScaleDirObj instanceof String
                ? (String) targetScaleDirObj : null;

        AutoAccessibilityService autoAccessibilityService = AutoAccessibilityService.get();
        if (autoAccessibilityService==null){
            return false;
        }
        final Activity a = ActivityHolder.getTopActivity();
        final Context captureContext = a != null ? a : autoAccessibilityService;
//        int currentPhysicalRotation = AutoAccessibilityService.getCurrentPhysicalRotation(autoAccessibilityService);

        /**
         * 然后这里需要注意的是 如果有 bbox 直接给它裁剪一下
         */
        Bitmap cropped = null;

        Bitmap full = ScreenCapture.captureLatestBitmap(
                null,
                TEMPLATE_CAPTURE_TIMEOUT_MS,
                TEMPLATE_CAPTURE_INTERVAL_MS
        );
        if (full == null || full.isRecycled()) {
            Log.w(TAG, "模板制作失败：未能获取有效截图，captureRunning="
                    + ScreenCaptureManager.getInstance().isRunning());
            toastOnMain("截图失败：录屏会话可能已失效，请重新授权后再试");
            notifyTemplateCaptureCancelled(projectName, taskName, saveFileName);
            return false;
        }
        List<Integer> stdBbox = new ArrayList<>();
        if (bbox!=null&&bbox.size()==4){
            Double x = bbox.get(0);
            Double y = bbox.get(1);
            Double w = bbox.get(2);
            Double h = bbox.get(3);
//        裁剪的bitmap
            cropped  = Bitmap.createBitmap(full, x.intValue(), y.intValue(), w.intValue(), h.intValue());
            stdBbox.add(x.intValue());
            stdBbox.add(y.intValue());
            stdBbox.add(w.intValue());
            stdBbox.add(h.intValue());
        }
//        /标准的bbox格式




        //调用结果  产生一个 response  放在 obj 或者 ctx里面
        Integer responseType = obj.getResponseType();
        // response =1 代表产生 默认的 response
        if (responseType==null||responseType==1){
            Map<String, Object> res = new HashMap<>();
            res.put(MetaOperation.FULLBITMAPRES,full);
            res.put(MetaOperation.CROPPEDBITMAPRES,cropped);
            ctx.currentResponse = res;
            ctx.lastOperation = obj;

            return true;
        } else if (responseType==null||responseType==2) {
            //这里比上一种多了一步操作 -->> 保存图片
//            保存图片
            saveImg(cropped,captureContext,projectName,taskName,saveFileName,stdBbox);

            Map<String, Object> res = new HashMap<>();
            res.put(MetaOperation.BBOX, stdBbox.isEmpty() ? null : stdBbox);
            res.put(MetaOperation.RESULT, saveFileName);
            ctx.currentResponse = res;
            ctx.lastOperation = obj;
            recycleBitmap(cropped);
            recycleBitmap(full);
            return true;
            //todo 记得回收
            //这里把mat 存在缓存里
//            OpenCVHelper cv = OpenCVHelper.getInstance();
//            Mat screenMat = cv.bitmapToMat(bitmap);
//            Template.putCache(projectName,"temp",screenMat);
//            bitmap.recycle();
        }else if (responseType==null||responseType==3){
            //手动选图！！！
            AutoAccessibilityService service = AutoAccessibilityService.get();
            if (service == null) return false;
            final Context ctx1 = service;
            final WindowManager wm = (WindowManager) ctx1.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;
            final int layoutType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

            final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                    PixelFormat.TRANSLUCENT
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            final SelectionOverlayView overlay = new SelectionOverlayView(ctx1);
            Bitmap finalFull = full;
            overlay.setFrozenBackground(finalFull, false);
            overlay.setRefineEnabled(true);
            overlay.setListener(new SelectionOverlayView.Listener() {
                @Override
                public void onConfirm(android.graphics.Rect rectInOverlay, Bitmap ignored) {
                    Rect coarseRect = new Rect(rectInOverlay);
                    normalizeRect(coarseRect);
                    overlay.setVisibility(View.INVISIBLE);
                    safeRemove(wm, overlay);
                    promptTemplateSaveMode(ctx1, coarseRect,
                            () -> {
                                promptTemplateMaskMode(ctx1, wm, finalFull, coarseRect,
                                        projectName, taskName, saveFileName, targetScaleDir,
                                        () -> recycleBitmap(finalFull));
                            },
                            () -> showTemplateRefinePointPicker(ctx1, wm, finalFull, coarseRect,
                                    finalRect -> {
                                        promptTemplateMaskMode(ctx1, wm, finalFull, finalRect,
                                                projectName, taskName, saveFileName, targetScaleDir,
                                                () -> recycleBitmap(finalFull));
                                    },
                                    () -> {
                                        recycleBitmap(finalFull);
                                        notifyTemplateCaptureCancelled(projectName, taskName, saveFileName);
                                    }),
                            () -> {
                                toastOnMain("已取消");
                                recycleBitmap(finalFull);
                                notifyTemplateCaptureCancelled(projectName, taskName, saveFileName);
                            });
                }

                @Override
                public void onCancel() {
                    toastOnMain("已取消");
                    safeRemove(wm, overlay);
                    recycleBitmap(finalFull);
                    notifyTemplateCaptureCancelled(projectName, taskName, saveFileName);
                }
            });
            overlay.setOnRefineRequestedListener(rectInOverlay -> {
                Rect coarseRect = new Rect(rectInOverlay);
                normalizeRect(coarseRect);
                overlay.setVisibility(View.INVISIBLE);
                safeRemove(wm, overlay);
                showTemplateRefinePointPicker(ctx1, wm, finalFull, coarseRect,
                        finalRect -> {
                            promptTemplateMaskMode(ctx1, wm, finalFull, finalRect,
                                    projectName, taskName, saveFileName, targetScaleDir,
                                    () -> recycleBitmap(finalFull));
                        },
                        () -> {
                            recycleBitmap(finalFull);
                            notifyTemplateCaptureCancelled(projectName, taskName, saveFileName);
                        });
            });

            getMainHandler().post(() -> {
                try {
                    wm.addView(overlay, params);
                    toastOnMain("拖动框选区域，调整后点“确定”保存");
                } catch (Throwable t) {
                    recycleBitmap(finalFull);
                    Log.e(TAG, "addView failed", t);
                    toastOnMain("创建选区失败：" + t.getMessage());
                    notifyTemplateCaptureCancelled(projectName, taskName, saveFileName);
                }
            });
            return true;
        }


        return false;
    }


}

package com.auto.master.Task.Handler.OperationHandler;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OcrTextOperation;
import com.auto.master.Task.Operation.OperationContext;
import com.auto.master.Task.Operation.OperationType;
import com.auto.master.auto.AutoAccessibilityService;
import com.auto.master.capture.ScreenCapture;
import com.googlecode.tesseract.android.TessBaseAPI;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class OcrTextOperationHandler extends OperationHandler {

    private static final String TAG = "OcrTextHandler";
    private static final Object TESS_LOCK = new Object();
    private static TessBaseAPI cachedApi;
    private static String cachedLanguage = "";
    private static String cachedDataPath = "";

    OcrTextOperationHandler() {
        this.setType(OperationType.OCR_TEXT.getCode());
    }

    @Override
    public boolean handle(MetaOperation obj, OperationContext ctx) {
        if (ctx == null) return false;
        if (ctx.variables == null) ctx.variables = new HashMap<>();
        ctx.currentOperation = obj;

        OcrTextOperation operation = (OcrTextOperation) obj;
        Map<String, Object> inputMap = operation.getInputMap();
        List<Integer> bbox = parseBbox(inputMap == null ? null : inputMap.get(MetaOperation.BBOX));
        if (bbox == null) {
            putFailure(ctx, obj, "missing_bbox", "OCR 区域为空", "");
            return true;
        }

        OcrOptions options = OcrOptions.fromInput(inputMap);
        String language = options.language;
        String textVar = getString(inputMap, MetaOperation.OCR_TEXT_VAR, "");
        String confidenceVar = getString(inputMap, MetaOperation.OCR_CONFIDENCE_VAR, "");
        int minConfidence = parseInt(inputMap == null ? null : inputMap.get(MetaOperation.OCR_MIN_CONFIDENCE), 0, 0, 100);
        int pageSegMode = parsePageSegMode(inputMap == null ? null : inputMap.get(MetaOperation.OCR_PAGE_SEG_MODE));
        long timeoutMs = parseLong(inputMap == null ? null : inputMap.get(MetaOperation.MATCHTIMEOUT),
                MetaOperation.DEFAULT_MATCH_TIMEOUT_MS, 1L, 60_000L);
        long preDelayMs = inputMap != null && inputMap.containsKey(MetaOperation.NODE_PRE_DELAY_MS)
                ? 0L
                : parseLong(inputMap == null ? null : inputMap.get(MetaOperation.MATCH_PRE_DELAY_MS),
                0L, 0L, MetaOperation.MAX_MATCH_DELAY_MS);

        Context appContext = getAppContext();
        if (appContext == null) {
            putFailure(ctx, obj, "no_context", "无障碍服务未连接，无法初始化 OCR", "");
            return true;
        }
        if (preDelayMs > 0L) {
            SystemClock.sleep(preDelayMs);
        }

        OcrPreviewResult preview = recognize(appContext, bbox, options, pageSegMode, minConfidence, timeoutMs);
        String lastText = preview.text;
        int lastConfidence = preview.confidence;
        boolean success = preview.success;
        String reason = preview.reason;

        if (!TextUtils.isEmpty(textVar)) {
            ctx.variables.put(textVar, lastText);
        }
        if (!TextUtils.isEmpty(confidenceVar)) {
            ctx.variables.put(confidenceVar, (long) lastConfidence);
        }

        Rect roi = rectFromBbox(bbox);
        if (success && !ctx.suppressVisualFeedback) {
            AutoAccessibilityService svc = AutoAccessibilityService.get();
            if (svc != null && getMainHandler() != null) {
                getMainHandler().post(() -> svc.showRectFeedback(
                        roi.left, roi.top, roi.width(), roi.height(),
                        420, 0x00000000, 0, 0x6638BDF8));
            }
        }

        HashMap<String, Object> response = new HashMap<>();
        response.put(MetaOperation.MATCHED, success);
        response.put(MetaOperation.RESULT, lastText);
        response.put(MetaOperation.OCR_TEXT, lastText);
        response.put(MetaOperation.OCR_CONFIDENCE, (long) lastConfidence);
        response.put(MetaOperation.BBOX, bbox);
        response.put("reason", reason);
        response.put("language", language);
        response.put("model", options.model);
        ctx.currentResponse = response;
        ctx.lastOperation = obj;
        ctx.currentOperation = obj;
        return true;
    }

    public static OcrPreviewResult recognizeOnce(Context context,
                                                 List<Integer> bbox,
                                                 String language,
                                                 float scaleFactor,
                                                 int threshold,
                                                 String pageSegMode) {
        int psm = parsePageSegMode(pageSegMode);
        OcrOptions options = OcrOptions.basic(language, scaleFactor, threshold);
        return recognize(context, bbox, options, psm, 0, 2_000L);
    }

    public static OcrPreviewResult recognizeOnce(Context context,
                                                 List<Integer> bbox,
                                                 OcrOptions options,
                                                 String pageSegMode) {
        int psm = parsePageSegMode(pageSegMode);
        return recognize(context, bbox, options == null ? OcrOptions.basic("chi_sim+eng", 1.0f, 0) : options,
                psm, 0, 2_000L, true);
    }

    public static Bitmap renderProcessedPreview(Context context,
                                                List<Integer> bbox,
                                                OcrOptions options) {
        if (context == null) {
            return null;
        }
        List<Integer> normalizedBbox = parseBbox(bbox);
        if (normalizedBbox == null) {
            return null;
        }
        Bitmap source = null;
        try {
            source = ScreenCapture.captureLatestBitmap(rectFromBbox(normalizedBbox), 500L, 40L);
            if (source == null || source.isRecycled()) {
                return null;
            }
            return preprocess(source, options);
        } catch (Exception e) {
            Log.w(TAG, "render OCR preview failed", e);
            return null;
        } finally {
            recycleIfNeeded(source);
        }
    }

    private static OcrPreviewResult recognize(Context context,
                                              List<Integer> bbox,
                                              OcrOptions options,
                                              int pageSegMode,
                                              int minConfidence,
                                              long timeoutMs) {
        return recognize(context, bbox, options, pageSegMode, minConfidence, timeoutMs, false);
    }

    private static OcrPreviewResult recognize(Context context,
                                              List<Integer> bbox,
                                              OcrOptions options,
                                              int pageSegMode,
                                              int minConfidence,
                                              long timeoutMs,
                                              boolean keepPreviewBitmap) {
        if (context == null) {
            return OcrPreviewResult.failure("no_context", "无可用 Context", "", 0);
        }
        List<Integer> normalizedBbox = parseBbox(bbox);
        if (normalizedBbox == null) {
            return OcrPreviewResult.failure("missing_bbox", "OCR 区域为空", "", 0);
        }
        OcrOptions safeOptions = options == null ? OcrOptions.basic("chi_sim+eng", 1.0f, 0) : options.normalized();
        String safeLanguage = safeOptions.language;
        TessDataResult dataResult = prepareTessData(context.getApplicationContext(), safeOptions);
        if (!dataResult.ready) {
            return OcrPreviewResult.failure("missing_tessdata", dataResult.message, "", 0);
        }

        Rect roi = rectFromBbox(normalizedBbox);
        long start = SystemClock.uptimeMillis();
        String lastText = "";
        int lastConfidence = 0;
        String reason = "timeout";
        String message = "";

        while (SystemClock.uptimeMillis() - start <= timeoutMs) {
            Bitmap bitmap = null;
            Bitmap prepared = null;
            try {
                bitmap = ScreenCapture.captureLatestBitmap(roi, 500L, 40L);
                if (bitmap == null || bitmap.isRecycled()) {
                    reason = "capture_failed";
                    message = "截图失败";
                    SystemClock.sleep(180L);
                    continue;
                }
                prepared = preprocess(bitmap, safeOptions);
                OcrResult result = runOcr(dataResult.dataPath, safeLanguage, prepared, pageSegMode);
                Bitmap previewBitmap = null;
                if (keepPreviewBitmap) {
                    previewBitmap = prepared;
                    prepared = null;
                }
                lastText = normalizeOcrText(result.text);
                if (safeOptions.digitsOnly) {
                    lastText = lastText.replaceAll("[^0-9]", "");
                }
                lastConfidence = result.confidence;
                boolean success = lastConfidence >= minConfidence;
                return success
                        ? OcrPreviewResult.success(lastText, lastConfidence, "recognized", previewBitmap)
                        : OcrPreviewResult.failure("low_confidence", "OCR 置信度低于阈值", lastText, lastConfidence, previewBitmap);
            } catch (Exception e) {
                reason = "ocr_failed";
                message = e.getMessage() == null ? "OCR 识别失败" : e.getMessage();
                Log.w(TAG, "OCR failed", e);
            } finally {
                recycleIfNeeded(prepared);
                recycleIfNeeded(bitmap);
            }
            SystemClock.sleep(300L);
        }
        return OcrPreviewResult.failure(reason, message, lastText, lastConfidence);
    }

    private static Rect rectFromBbox(List<Integer> bbox) {
        return new Rect(bbox.get(0), bbox.get(1), bbox.get(0) + bbox.get(2), bbox.get(1) + bbox.get(3));
    }

    private static OcrResult runOcr(String dataPath, String language, Bitmap bitmap, int pageSegMode) {
        synchronized (TESS_LOCK) {
            TessBaseAPI api = getOrCreateApi(dataPath, language);
            api.setPageSegMode(pageSegMode);
            api.setImage(bitmap);
            String text = api.getUTF8Text();
            int confidence = api.meanConfidence();
            api.clear();
            return new OcrResult(text == null ? "" : text, Math.max(0, Math.min(100, confidence)));
        }
    }

    private static TessBaseAPI getOrCreateApi(String dataPath, String language) {
        if (cachedApi != null
                && TextUtils.equals(cachedLanguage, language)
                && TextUtils.equals(cachedDataPath, dataPath)) {
            return cachedApi;
        }
        if (cachedApi != null) {
            cachedApi.recycle();
            cachedApi = null;
        }
        TessBaseAPI api = new TessBaseAPI();
        if (!api.init(dataPath, language)) {
            api.recycle();
            throw new IllegalStateException("Tesseract 初始化失败: " + language);
        }
        cachedApi = api;
        cachedLanguage = language;
        cachedDataPath = dataPath;
        return api;
    }

    private static Bitmap preprocess(Bitmap source, OcrOptions options) {
        OcrOptions safeOptions = options == null ? OcrOptions.basic("chi_sim+eng", 1.0f, 0) : options.normalized();
        if (safeOptions.hasPreprocessPipeline()) {
            return preprocessByPipeline(source, safeOptions);
        }
        Bitmap workingSource = source;
        if (safeOptions.useMask && !TextUtils.isEmpty(safeOptions.maskPngBase64)) {
            workingSource = source.copy(Bitmap.Config.ARGB_8888, true);
            applyMaskFill(workingSource, safeOptions.maskPngBase64);
        }

        Bitmap scaled = workingSource;
        float resizeFactor = (safeOptions.compressPercent / 100.0f) * safeOptions.scaleFactor;
        if (Math.abs(resizeFactor - 1.0f) > 0.01f) {
            int w = Math.max(1, Math.round(workingSource.getWidth() * resizeFactor));
            int h = Math.max(1, Math.round(workingSource.getHeight() * resizeFactor));
            scaled = Bitmap.createScaledBitmap(workingSource, w, h, true);
        }
        Bitmap out = Bitmap.createBitmap(scaled.getWidth(), scaled.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (safeOptions.grayscale) {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        }
        canvas.drawBitmap(scaled, 0, 0, paint);
        if (scaled != workingSource) {
            recycleIfNeeded(scaled);
        }
        if (workingSource != source) {
            recycleIfNeeded(workingSource);
        }
        if (safeOptions.contrastPercent != 100) {
            applyContrast(out, safeOptions.contrastPercent);
        }
        if (safeOptions.medianDenoise) {
            applyMedianDenoise(out);
        }
        if (safeOptions.gaussianBlur && safeOptions.gaussianRadius > 0) {
            applyGaussianBlur(out, safeOptions.gaussianRadius);
        }
        if (safeOptions.sharpen) {
            applySharpen(out);
        }
        if (safeOptions.grayscale && safeOptions.threshold > 0) {
            applyThreshold(out, safeOptions.threshold);
        }
        if (safeOptions.invert) {
            applyInvert(out);
        }
        if (!"none".equals(safeOptions.morphMode) && safeOptions.morphIterations > 0) {
            applyMorphology(out, safeOptions.morphMode, safeOptions.morphIterations);
        }
        return out;
    }

    private static Bitmap preprocessByPipeline(Bitmap source, OcrOptions options) {
        Bitmap current = source.copy(Bitmap.Config.ARGB_8888, true);
        for (Map<String, Object> step : options.preprocessPipeline) {
            if (step == null || !parseBoolean(step.get("enabled"), true)) {
                continue;
            }
            String type = getString(step, "type", "").toLowerCase(Locale.ROOT);
            if (TextUtils.isEmpty(type)) {
                continue;
            }
            Bitmap next = current;
            if ("mask".equals(type)) {
                String mask = getString(step, "mask", options.maskPngBase64);
                if (!TextUtils.isEmpty(mask)) {
                    applyMaskFill(next, mask);
                }
            } else if ("resize".equals(type) || "scale".equals(type)) {
                float factor = parseFloat(step.get("factor"), options.scaleFactor, 0.1f, 8.0f);
                int percent = parseInt(step.get("percent"), 100, 1, 400);
                float resizeFactor = factor * percent / 100.0f;
                if (Math.abs(resizeFactor - 1.0f) > 0.01f) {
                    int w = Math.max(1, Math.round(current.getWidth() * resizeFactor));
                    int h = Math.max(1, Math.round(current.getHeight() * resizeFactor));
                    next = Bitmap.createScaledBitmap(current, w, h, true);
                }
            } else if ("grayscale".equals(type) || "gray".equals(type)) {
                applyGrayscale(next);
            } else if ("contrast".equals(type)) {
                applyContrast(next, parseInt(step.get("percent"), options.contrastPercent, 50, 300));
            } else if ("median".equals(type) || "denoise".equals(type) || "median_denoise".equals(type)) {
                applyMedianDenoise(next);
            } else if ("gaussian".equals(type) || "blur".equals(type) || "gaussian_blur".equals(type)) {
                applyGaussianBlur(next, parseInt(step.get("radius"), options.gaussianRadius, 0, 10));
            } else if ("sharpen".equals(type)) {
                applySharpen(next);
            } else if ("threshold".equals(type) || "binary".equals(type)) {
                applyThreshold(next, parseInt(step.get("value"), options.threshold, 0, 255));
            } else if ("invert".equals(type)) {
                applyInvert(next);
            } else if ("morph".equals(type) || "morphology".equals(type)) {
                String mode = normalizeMorphMode(getString(step, "mode", options.morphMode));
                int iterations = parseInt(step.get("iterations"), options.morphIterations, 0, 3);
                applyMorphology(next, mode, iterations);
            }
            if (next != current) {
                recycleIfNeeded(current);
                current = next;
            }
        }
        return current;
    }

    private static void applyGrayscale(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int gray = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3;
            pixels[i] = Color.argb(Color.alpha(c), gray, gray, gray);
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void applyMaskFill(Bitmap bitmap, String maskPngBase64) {
        Bitmap mask = null;
        Bitmap scaledMask = null;
        try {
            byte[] bytes = Base64.decode(stripDataUriPrefix(maskPngBase64), Base64.DEFAULT);
            mask = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (mask == null || mask.isRecycled()) {
                return;
            }
            Bitmap activeMask = mask;
            if (mask.getWidth() != bitmap.getWidth() || mask.getHeight() != bitmap.getHeight()) {
                scaledMask = Bitmap.createScaledBitmap(mask, bitmap.getWidth(), bitmap.getHeight(), false);
                activeMask = scaledMask;
            }
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            int[] maskPixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            activeMask.getPixels(maskPixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < pixels.length; i++) {
                int m = maskPixels[i];
                int v = Math.max(Color.red(m), Math.max(Color.green(m), Color.blue(m)));
                if (v > 0) {
                    pixels[i] = Color.WHITE;
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        } catch (Exception e) {
            Log.w(TAG, "apply OCR mask failed", e);
        } finally {
            recycleIfNeeded(scaledMask);
            recycleIfNeeded(mask);
        }
    }

    private static String stripDataUriPrefix(String raw) {
        if (raw == null) return "";
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma >= 0 && comma < raw.length() - 1) {
            return raw.substring(comma + 1);
        }
        return raw;
    }

    private static void applyThreshold(Bitmap bitmap, int threshold) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int gray = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3;
            pixels[i] = gray >= threshold ? Color.WHITE : Color.BLACK;
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void applyContrast(Bitmap bitmap, int contrastPercent) {
        float factor = Math.max(50, Math.min(300, contrastPercent)) / 100f;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            pixels[i] = Color.argb(Color.alpha(c),
                    clamp(Math.round((Color.red(c) - 128) * factor + 128), 0, 255),
                    clamp(Math.round((Color.green(c) - 128) * factor + 128), 0, 255),
                    clamp(Math.round((Color.blue(c) - 128) * factor + 128), 0, 255));
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void applyMedianDenoise(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 3 || h < 3) return;
        int[] src = new int[w * h];
        int[] out = new int[w * h];
        bitmap.getPixels(src, 0, w, 0, 0, w, h);
        System.arraycopy(src, 0, out, 0, src.length);
        int[] ar = new int[9];
        int[] rr = new int[9];
        int[] gg = new int[9];
        int[] bb = new int[9];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int c = src[(y + dy) * w + x + dx];
                        ar[idx] = Color.alpha(c);
                        rr[idx] = Color.red(c);
                        gg[idx] = Color.green(c);
                        bb[idx] = Color.blue(c);
                        idx++;
                    }
                }
                Arrays.sort(ar);
                Arrays.sort(rr);
                Arrays.sort(gg);
                Arrays.sort(bb);
                out[y * w + x] = Color.argb(ar[4], rr[4], gg[4], bb[4]);
            }
        }
        bitmap.setPixels(out, 0, w, 0, 0, w, h);
    }

    private static void applySharpen(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 3 || h < 3) return;
        int[] src = new int[w * h];
        int[] out = new int[w * h];
        bitmap.getPixels(src, 0, w, 0, 0, w, h);
        System.arraycopy(src, 0, out, 0, src.length);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int center = src[y * w + x];
                int up = src[(y - 1) * w + x];
                int down = src[(y + 1) * w + x];
                int left = src[y * w + x - 1];
                int right = src[y * w + x + 1];
                out[y * w + x] = Color.argb(Color.alpha(center),
                        clamp(Color.red(center) * 5 - Color.red(up) - Color.red(down) - Color.red(left) - Color.red(right), 0, 255),
                        clamp(Color.green(center) * 5 - Color.green(up) - Color.green(down) - Color.green(left) - Color.green(right), 0, 255),
                        clamp(Color.blue(center) * 5 - Color.blue(up) - Color.blue(down) - Color.blue(left) - Color.blue(right), 0, 255));
            }
        }
        bitmap.setPixels(out, 0, w, 0, 0, w, h);
    }

    private static void applyInvert(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            pixels[i] = Color.argb(Color.alpha(c),
                    255 - Color.red(c),
                    255 - Color.green(c),
                    255 - Color.blue(c));
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void applyMorphology(Bitmap bitmap, String mode, int iterations) {
        int safeIterations = Math.max(0, Math.min(3, iterations));
        String safeMode = TextUtils.isEmpty(mode) ? "none" : mode;
        for (int i = 0; i < safeIterations; i++) {
            if ("dilate".equals(safeMode)) {
                morphBlackForeground(bitmap, true);
            } else if ("erode".equals(safeMode)) {
                morphBlackForeground(bitmap, false);
            } else if ("open".equals(safeMode)) {
                morphBlackForeground(bitmap, false);
                morphBlackForeground(bitmap, true);
            } else if ("close".equals(safeMode)) {
                morphBlackForeground(bitmap, true);
                morphBlackForeground(bitmap, false);
            }
        }
    }

    private static void morphBlackForeground(Bitmap bitmap, boolean dilate) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 3 || h < 3) return;
        int[] src = new int[w * h];
        int[] out = new int[w * h];
        bitmap.getPixels(src, 0, w, 0, 0, w, h);
        System.arraycopy(src, 0, out, 0, src.length);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                boolean black = dilate ? false : true;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int c = src[(y + dy) * w + x + dx];
                        int gray = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3;
                        boolean isBlack = gray < 128;
                        black = dilate ? (black || isBlack) : (black && isBlack);
                    }
                }
                out[y * w + x] = black ? Color.BLACK : Color.WHITE;
            }
        }
        bitmap.setPixels(out, 0, w, 0, 0, w, h);
    }

    private static void applyGaussianBlur(Bitmap bitmap, int radius) {
        int safeRadius = Math.max(1, Math.min(10, radius));
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] src = new int[w * h];
        int[] tmp = new int[w * h];
        int[] out = new int[w * h];
        bitmap.getPixels(src, 0, w, 0, 0, w, h);
        float[] kernel = buildGaussianKernel(safeRadius);
        int size = safeRadius * 2 + 1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float a = 0f, r = 0f, g = 0f, b = 0f;
                for (int k = 0; k < size; k++) {
                    int sx = clamp(x + k - safeRadius, 0, w - 1);
                    int c = src[y * w + sx];
                    float weight = kernel[k];
                    a += Color.alpha(c) * weight;
                    r += Color.red(c) * weight;
                    g += Color.green(c) * weight;
                    b += Color.blue(c) * weight;
                }
                tmp[y * w + x] = Color.argb(clamp(Math.round(a), 0, 255),
                        clamp(Math.round(r), 0, 255),
                        clamp(Math.round(g), 0, 255),
                        clamp(Math.round(b), 0, 255));
            }
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float a = 0f, r = 0f, g = 0f, b = 0f;
                for (int k = 0; k < size; k++) {
                    int sy = clamp(y + k - safeRadius, 0, h - 1);
                    int c = tmp[sy * w + x];
                    float weight = kernel[k];
                    a += Color.alpha(c) * weight;
                    r += Color.red(c) * weight;
                    g += Color.green(c) * weight;
                    b += Color.blue(c) * weight;
                }
                out[y * w + x] = Color.argb(clamp(Math.round(a), 0, 255),
                        clamp(Math.round(r), 0, 255),
                        clamp(Math.round(g), 0, 255),
                        clamp(Math.round(b), 0, 255));
            }
        }
        bitmap.setPixels(out, 0, w, 0, 0, w, h);
    }

    private static float[] buildGaussianKernel(int radius) {
        int size = radius * 2 + 1;
        float[] kernel = new float[size];
        double sigma = Math.max(0.8d, radius / 2.0d);
        double sum = 0d;
        for (int i = 0; i < size; i++) {
            int x = i - radius;
            double value = Math.exp(-(x * x) / (2d * sigma * sigma));
            kernel[i] = (float) value;
            sum += value;
        }
        if (sum <= 0d) {
            return kernel;
        }
        for (int i = 0; i < size; i++) {
            kernel[i] /= (float) sum;
        }
        return kernel;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static TessDataResult prepareTessData(Context context, OcrOptions options) {
        File internalRoot = new File(context.getFilesDir(), "tesseract");
        File internalDir = new File(internalRoot, "tessdata");
        if (!internalDir.exists() && !internalDir.mkdirs()) {
            return TessDataResult.missing("无法创建 tessdata 目录: " + internalDir.getAbsolutePath(), internalRoot.getAbsolutePath());
        }
        copyBundledTrainedData(context, internalDir);
        List<String> missing = missingTrainedData(internalDir, options);
        if (missing.isEmpty()) {
            return TessDataResult.ready(internalRoot.getAbsolutePath());
        }

        File externalBase = context.getExternalFilesDir(null);
        if (externalBase != null) {
            File externalRoot = new File(externalBase, "tesseract");
            File externalDir = new File(externalRoot, "tessdata");
            if (externalDir.exists() && missingTrainedData(externalDir, options).isEmpty()) {
                return TessDataResult.ready(externalRoot.getAbsolutePath());
            }
        }
        String message = "缺少 OCR 训练数据: " + TextUtils.join(", ", missing)
                + "。请将对应 *.traineddata 放入 assets/tessdata 后重新打包，或放到 "
                + internalDir.getAbsolutePath();
        return TessDataResult.missing(message, internalRoot.getAbsolutePath());
    }

    private static void copyBundledTrainedData(Context context, File targetDir) {
        try {
            AssetManager assets = context.getAssets();
            copyBundledTrainedDataFromAssetDir(assets, targetDir, "tessdata");
            copyBundledTrainedDataFromAssetDir(assets, targetDir, "");
        } catch (Exception e) {
            Log.w(TAG, "copy bundled tessdata failed", e);
        }
    }

    private static void copyBundledTrainedDataFromAssetDir(AssetManager assets, File targetDir, String assetDir) throws Exception {
        String[] names = assets.list(assetDir);
        if (names == null) return;
        for (String name : names) {
            if (TextUtils.isEmpty(name) || !name.endsWith(".traineddata")) {
                continue;
            }
            File out = new File(targetDir, name);
            if (out.exists() && out.length() > 0) {
                continue;
            }
            String assetPath = TextUtils.isEmpty(assetDir) ? name : assetDir + "/" + name;
            try (InputStream is = assets.open(assetPath);
                 FileOutputStream fos = new FileOutputStream(out, false)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, n);
                }
                fos.flush();
            }
        }
    }

    private static List<String> missingTrainedData(File tessdataDir, OcrOptions options) {
        Set<String> required = new LinkedHashSet<>();
        OcrOptions safeOptions = options == null ? OcrOptions.basic("chi_sim+eng", 1.0f, 0) : options.normalized();
        for (String lang : splitLanguages(safeOptions.language)) {
            required.add(lang + ".traineddata");
        }
        if (!TextUtils.isEmpty(safeOptions.trainedDataFile)) {
            required.add(safeOptions.trainedDataFile);
        }
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            File file = new File(tessdataDir, name);
            if (!file.exists() || file.length() <= 0) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static List<String> splitLanguages(String language) {
        if (TextUtils.isEmpty(language)) {
            return Arrays.asList("eng");
        }
        String[] parts = language.split("\\+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String lang = part == null ? "" : part.trim();
            if (!lang.isEmpty()) {
                result.add(lang);
            }
        }
        return result.isEmpty() ? Arrays.asList("eng") : result;
    }

    private static String normalizeOcrText(String raw) {
        if (raw == null) return "";
        return raw.replace("\r", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?<=[\\u4E00-\\u9FFF])\\s+(?=[\\u4E00-\\u9FFF])", "")
                .trim();
    }

    private static Context getAppContext() {
        AutoAccessibilityService svc = AutoAccessibilityService.get();
        return svc == null ? null : svc.getApplicationContext();
    }

    private void putFailure(OperationContext ctx, MetaOperation obj, String reason, String message, String text) {
        HashMap<String, Object> response = new HashMap<>();
        response.put(MetaOperation.MATCHED, false);
        response.put(MetaOperation.RESULT, text == null ? "" : text);
        response.put(MetaOperation.OCR_TEXT, text == null ? "" : text);
        response.put(MetaOperation.OCR_CONFIDENCE, 0L);
        response.put("reason", reason);
        response.put("error", message == null ? "" : message);
        ctx.currentResponse = response;
        ctx.lastOperation = obj;
        ctx.currentOperation = obj;
    }

    private static List<Integer> parseBbox(Object raw) {
        if (!(raw instanceof List)) return null;
        List<?> values = (List<?>) raw;
        if (values.size() < 4) return null;
        try {
            int x = toInt(values.get(0));
            int y = toInt(values.get(1));
            int w = Math.max(1, toInt(values.get(2)));
            int h = Math.max(1, toInt(values.get(3)));
            return Arrays.asList(x, y, w, h);
        } catch (Exception e) {
            return null;
        }
    }

    private static int toInt(Object raw) {
        if (raw instanceof Number) return ((Number) raw).intValue();
        return Integer.parseInt(String.valueOf(raw).trim());
    }

    private static String getString(Map<String, Object> inputMap, String key, String def) {
        if (inputMap == null || inputMap.get(key) == null) return def;
        String text = String.valueOf(inputMap.get(key)).trim();
        return TextUtils.isEmpty(text) ? def : text;
    }

    private static int parseInt(Object raw, int def, int min, int max) {
        int value = def;
        if (raw instanceof Number) {
            value = ((Number) raw).intValue();
        } else if (raw instanceof String) {
            try {
                value = Integer.parseInt(((String) raw).trim());
            } catch (Exception ignored) {
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static long parseLong(Object raw, long def, long min, long max) {
        long value = def;
        if (raw instanceof Number) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof String) {
            try {
                value = Long.parseLong(((String) raw).trim());
            } catch (Exception ignored) {
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static float parseFloat(Object raw, float def, float min, float max) {
        float value = def;
        if (raw instanceof Number) {
            value = ((Number) raw).floatValue();
        } else if (raw instanceof String) {
            try {
                value = Float.parseFloat(((String) raw).trim());
            } catch (Exception ignored) {
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static boolean parseBoolean(Object raw, boolean def) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) return false;
        }
        return def;
    }

    private static List<Map<String, Object>> parsePreprocessPipeline(Object raw) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (raw == null) {
            return steps;
        }
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                Map<String, Object> step = objectToMap(item);
                if (step != null && !step.isEmpty()) {
                    steps.add(step);
                }
            }
            return steps;
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (TextUtils.isEmpty(text)) {
                return steps;
            }
            try {
                JSONArray array = new JSONArray(text);
                for (int i = 0; i < array.length(); i++) {
                    Map<String, Object> step = objectToMap(array.opt(i));
                    if (step != null && !step.isEmpty()) {
                        steps.add(step);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "parse OCR preprocess pipeline failed", e);
            }
        }
        return steps;
    }

    private static List<Map<String, Object>> normalizePreprocessPipeline(List<Map<String, Object>> rawSteps) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (rawSteps == null) {
            return steps;
        }
        for (Map<String, Object> rawStep : rawSteps) {
            if (rawStep == null) {
                continue;
            }
            String type = getString(rawStep, "type", "").trim().toLowerCase(Locale.ROOT);
            if (TextUtils.isEmpty(type)) {
                continue;
            }
            Map<String, Object> step = new HashMap<>();
            step.putAll(rawStep);
            step.put("type", type);
            steps.add(step);
        }
        return steps;
    }

    private static Map<String, Object> objectToMap(Object raw) {
        if (raw instanceof Map) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            Map<String, Object> out = new HashMap<>();
            JSONArray names = object.names();
            if (names == null) {
                return out;
            }
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                Object value = object.opt(key);
                if (!TextUtils.isEmpty(key) && value != null && value != JSONObject.NULL) {
                    out.put(key, value);
                }
            }
            return out;
        }
        return null;
    }

    private static String normalizeMorphMode(String raw) {
        if (TextUtils.isEmpty(raw)) return "none";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("加粗".equals(raw) || "膨胀".equals(raw) || "dilate".equals(value)) return "dilate";
        if ("腐蚀".equals(raw) || "细化".equals(raw) || "erode".equals(value)) return "erode";
        if ("开运算".equals(raw) || "open".equals(value)) return "open";
        if ("闭运算".equals(raw) || "close".equals(value)) return "close";
        return "none";
    }

    private static int parsePageSegMode(Object raw) {
        String text = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(text) || "auto".equals(text)) return 3;
        if ("block".equals(text) || "single_block".equals(text)) return 6;
        if ("line".equals(text) || "single_line".equals(text)) return 7;
        if ("word".equals(text) || "single_word".equals(text)) return 8;
        if ("sparse".equals(text) || "sparse_text".equals(text)) return 11;
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return 3;
        }
    }

    private static void recycleIfNeeded(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static final class OcrResult {
        final String text;
        final int confidence;

        OcrResult(String text, int confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    public static final class OcrOptions {
        public static final String MODEL_CHINESE = "chinese";
        public static final String MODEL_ENGLISH = "english";
        public static final String MODEL_NUMBER = "number";
        public static final String MODEL_CHI_ENG = "chi_eng";
        public static final String MODEL_CUSTOM = "custom";

        public final String model;
        public final String language;
        public final String trainedDataFile;
        public final boolean grayscale;
        public final int threshold;
        public final int compressPercent;
        public final float scaleFactor;
        public final boolean gaussianBlur;
        public final int gaussianRadius;
        public final boolean useMask;
        public final String maskPngBase64;
        public final boolean invert;
        public final int contrastPercent;
        public final boolean medianDenoise;
        public final boolean sharpen;
        public final String morphMode;
        public final int morphIterations;
        public final List<Map<String, Object>> preprocessPipeline;
        public final boolean digitsOnly;

        private OcrOptions(String model,
                           String language,
                           String trainedDataFile,
                           boolean grayscale,
                           int threshold,
                           int compressPercent,
                           float scaleFactor,
                           boolean gaussianBlur,
                           int gaussianRadius,
                           boolean useMask,
                           String maskPngBase64,
                           boolean invert,
                           int contrastPercent,
                           boolean medianDenoise,
                           boolean sharpen,
                           String morphMode,
                           int morphIterations,
                           List<Map<String, Object>> preprocessPipeline,
                           boolean digitsOnly) {
            this.model = model;
            this.language = language;
            this.trainedDataFile = trainedDataFile;
            this.grayscale = grayscale;
            this.threshold = threshold;
            this.compressPercent = compressPercent;
            this.scaleFactor = scaleFactor;
            this.gaussianBlur = gaussianBlur;
            this.gaussianRadius = gaussianRadius;
            this.useMask = useMask;
            this.maskPngBase64 = maskPngBase64;
            this.invert = invert;
            this.contrastPercent = contrastPercent;
            this.medianDenoise = medianDenoise;
            this.sharpen = sharpen;
            this.morphMode = morphMode;
            this.morphIterations = morphIterations;
            this.preprocessPipeline = preprocessPipeline == null ? new ArrayList<>() : preprocessPipeline;
            this.digitsOnly = digitsOnly;
        }

        static OcrOptions basic(String language, float scaleFactor, int threshold) {
            return new OcrOptions(MODEL_CUSTOM,
                    TextUtils.isEmpty(language) ? "chi_sim+eng" : language,
                    "",
                    false,
                    threshold,
                    100,
                    scaleFactor,
                    false,
                    0,
                    false,
                    "",
                    false,
                    100,
                    false,
                    false,
                    "none",
                    0,
                    new ArrayList<>(),
                    false).normalized();
        }

        public static OcrOptions fromInput(Map<String, Object> inputMap) {
            String rawModel = getString(inputMap, MetaOperation.OCR_MODEL, MODEL_CHI_ENG);
            String model = normalizeModel(rawModel);
            String language = getString(inputMap, MetaOperation.OCR_LANGUAGE, defaultLanguageForModel(model));
            String trainedData = getString(inputMap, MetaOperation.OCR_TRAINED_DATA, defaultTrainedDataForModel(model));
            boolean defaultDigitsOnly = MODEL_NUMBER.equals(model);
            return new OcrOptions(
                    model,
                    language,
                    trainedData,
                    parseBoolean(inputMap == null ? null : inputMap.get(MetaOperation.OCR_GRAYSCALE), false),
                    parseInt(inputMap == null ? null : inputMap.get(MetaOperation.OCR_THRESHOLD), 0, 0, 255),
                    parseInt(inputMap == null ? null : inputMap.get(MetaOperation.OCR_COMPRESS_PERCENT), 100, 10, 100),
                    parseFloat(inputMap == null ? null : inputMap.get(MetaOperation.OCR_SCALE_FACTOR), 1.0f, 0.25f, 4.0f),
                    parseBoolean(inputMap == null ? null : inputMap.get(MetaOperation.OCR_GAUSSIAN_BLUR), false),
                    parseInt(inputMap == null ? null : inputMap.get(MetaOperation.OCR_GAUSSIAN_RADIUS), 0, 0, 10),
                    parseBoolean(inputMap == null ? null : inputMap.get(MetaOperation.OCR_USE_MASK), false),
                    getString(inputMap, MetaOperation.OCR_MASK_PNG, ""),
                    parseBoolean(inputMap == null ? null : inputMap.get(MetaOperation.OCR_INVERT), false),
                    parseInt(inputMap == null ? null : inputMap.get(MetaOperation.OCR_CONTRAST_PERCENT), 100, 50, 300),
                    parseBoolean(inputMap == null ? null : inputMap.get(MetaOperation.OCR_MEDIAN_DENOISE), false),
                    parseBoolean(inputMap == null ? null : inputMap.get(MetaOperation.OCR_SHARPEN), false),
                    normalizeMorphMode(getString(inputMap, MetaOperation.OCR_MORPH_MODE, "none")),
                    parseInt(inputMap == null ? null : inputMap.get(MetaOperation.OCR_MORPH_ITERATIONS), 0, 0, 3),
                    parsePreprocessPipeline(inputMap == null ? null : inputMap.get(MetaOperation.OCR_PREPROCESS_PIPELINE)),
                    parseBoolean(inputMap == null ? null : inputMap.get(MetaOperation.OCR_DIGITS_ONLY), defaultDigitsOnly)
            ).normalized();
        }

        OcrOptions normalized() {
            String safeModel = normalizeModel(model);
            String safeLanguage = TextUtils.isEmpty(language) ? defaultLanguageForModel(safeModel) : language.trim();
            String safeData = TextUtils.isEmpty(trainedDataFile) ? defaultTrainedDataForModel(safeModel) : trainedDataFile.trim();
            if (!TextUtils.isEmpty(safeData) && !safeData.endsWith(".traineddata")) {
                safeData = safeData + ".traineddata";
            }
            boolean safeDigitsOnly = digitsOnly || MODEL_NUMBER.equals(safeModel);
            if (MODEL_NUMBER.equals(safeModel)) {
                safeLanguage = "eng";
                safeData = "eng.traineddata";
            }
            return new OcrOptions(
                    safeModel,
                    safeLanguage,
                    safeData,
                    grayscale,
                    Math.max(0, Math.min(255, threshold)),
                    Math.max(10, Math.min(100, compressPercent)),
                    Math.max(0.25f, Math.min(4.0f, scaleFactor)),
                    gaussianBlur,
                    Math.max(0, Math.min(10, gaussianRadius)),
                    useMask && !TextUtils.isEmpty(maskPngBase64),
                    maskPngBase64 == null ? "" : maskPngBase64.trim(),
                    invert,
                    Math.max(50, Math.min(300, contrastPercent)),
                    medianDenoise,
                    sharpen,
                    normalizeMorphMode(morphMode),
                    Math.max(0, Math.min(3, morphIterations)),
                    normalizePreprocessPipeline(preprocessPipeline),
                    safeDigitsOnly
            );
        }

        boolean hasPreprocessPipeline() {
            return preprocessPipeline != null && !preprocessPipeline.isEmpty();
        }

        private static String normalizeMorphMode(String raw) {
            if (TextUtils.isEmpty(raw)) return "none";
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if ("加粗".equals(raw) || "膨胀".equals(raw) || "dilate".equals(value)) return "dilate";
            if ("腐蚀".equals(raw) || "细化".equals(raw) || "erode".equals(value)) return "erode";
            if ("开运算".equals(raw) || "open".equals(value)) return "open";
            if ("闭运算".equals(raw) || "close".equals(value)) return "close";
            return "none";
        }

        private static String normalizeModel(String raw) {
            if (TextUtils.isEmpty(raw)) return MODEL_CHI_ENG;
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if ("识别中文".equals(raw) || "中文".equals(raw) || "chinese".equals(value)
                    || "chi_sim".equals(value) || "chi_sim.traineddata".equals(value)) {
                return MODEL_CHINESE;
            }
            if ("识别英文".equals(raw) || "英文".equals(raw) || "english".equals(value)
                    || "eng".equals(value) || "eng.traineddata".equals(value)) {
                return MODEL_ENGLISH;
            }
            if ("识别数字".equals(raw) || "数字".equals(raw) || "number".equals(value)
                    || "digits".equals(value) || "number.traineddata".equals(value)) {
                return MODEL_NUMBER;
            }
            if ("自定义模型".equals(raw) || "自定义".equals(raw) || "custom".equals(value)
                    || "custom.traineddata".equals(value)) {
                return MODEL_CUSTOM;
            }
            return MODEL_CHI_ENG;
        }

        private static String defaultLanguageForModel(String model) {
            if (MODEL_CHINESE.equals(model)) return "chi_sim";
            if (MODEL_ENGLISH.equals(model) || MODEL_NUMBER.equals(model)) return "eng";
            if (MODEL_CUSTOM.equals(model)) return "chi_sim";
            return "chi_sim+eng";
        }

        private static String defaultTrainedDataForModel(String model) {
            if (MODEL_CHINESE.equals(model)) return "chi_sim.traineddata";
            if (MODEL_ENGLISH.equals(model) || MODEL_NUMBER.equals(model)) return "eng.traineddata";
            return "";
        }
    }

    public static final class OcrPreviewResult {
        public final boolean success;
        public final String text;
        public final int confidence;
        public final String reason;
        public final String message;
        public final Bitmap previewBitmap;

        private OcrPreviewResult(boolean success, String text, int confidence, String reason, String message, Bitmap previewBitmap) {
            this.success = success;
            this.text = text == null ? "" : text;
            this.confidence = confidence;
            this.reason = reason == null ? "" : reason;
            this.message = message == null ? "" : message;
            this.previewBitmap = previewBitmap;
        }

        static OcrPreviewResult success(String text, int confidence, String reason) {
            return success(text, confidence, reason, null);
        }

        static OcrPreviewResult success(String text, int confidence, String reason, Bitmap previewBitmap) {
            return new OcrPreviewResult(true, text, confidence, reason, "", previewBitmap);
        }

        static OcrPreviewResult failure(String reason, String message, String text, int confidence) {
            return failure(reason, message, text, confidence, null);
        }

        static OcrPreviewResult failure(String reason, String message, String text, int confidence, Bitmap previewBitmap) {
            return new OcrPreviewResult(false, text, confidence, reason, message, previewBitmap);
        }
    }

    private static final class TessDataResult {
        final boolean ready;
        final String dataPath;
        final String message;

        private TessDataResult(boolean ready, String dataPath, String message) {
            this.ready = ready;
            this.dataPath = dataPath;
            this.message = message;
        }

        static TessDataResult ready(String dataPath) {
            return new TessDataResult(true, dataPath, "");
        }

        static TessDataResult missing(String message, String dataPath) {
            return new TessDataResult(false, dataPath, message);
        }
    }
}

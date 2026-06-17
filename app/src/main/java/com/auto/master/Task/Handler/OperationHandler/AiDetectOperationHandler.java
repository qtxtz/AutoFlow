package com.auto.master.Task.Handler.OperationHandler;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.auto.master.AtommApplication;
import com.auto.master.ProjectDataUtil;
import com.auto.master.Task.Operation.AiDetectOperation;
import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OperationContext;
import com.auto.master.Task.Operation.OperationType;
import com.auto.master.auto.AutoAccessibilityService;
import com.auto.master.capture.ScreenCapture;
import com.auto.master.utils.AppStorage;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AiDetectOperationHandler extends OperationHandler {
    private static final String TAG = "AiDetectHandler";
    private static final Object INTERPRETER_LOCK = new Object();
    private static Interpreter cachedInterpreter;
    private static String cachedModelPath = "";

    AiDetectOperationHandler() {
        this.setType(OperationType.AI_DETECT.getCode());
    }

    @Override
    public boolean handle(MetaOperation obj, OperationContext ctx) {
        if (ctx == null) return false;
        if (ctx.variables == null) ctx.variables = new HashMap<>();
        ctx.currentOperation = obj;

        AiDetectOperation operation = (AiDetectOperation) obj;
        Map<String, Object> inputMap = operation.getInputMap();
        Context appContext = getAppContext();
        if (appContext == null) {
            putFailure(ctx, obj, "no_context", "无可用 Context");
            return true;
        }

        List<Integer> roi = parseBbox(inputMap == null ? null : inputMap.get(MetaOperation.BBOX));
        if (roi == null) {
            putFailure(ctx, obj, "missing_bbox", "AI 识别区域为空");
            return true;
        }
        String modelPathText = getString(inputMap, MetaOperation.AI_MODEL_PATH, "");
        File modelFile = resolveFile(appContext, modelPathText);
        if (modelFile == null || !modelFile.exists() || !modelFile.isFile()) {
            putFailure(ctx, obj, "missing_model", "模型文件不存在: " + modelPathText);
            return true;
        }

        long preDelayMs = inputMap != null && inputMap.containsKey(MetaOperation.NODE_PRE_DELAY_MS)
                ? 0L
                : parseLong(inputMap == null ? null : inputMap.get(MetaOperation.MATCH_PRE_DELAY_MS),
                0L, 0L, MetaOperation.MAX_MATCH_DELAY_MS);
        if (preDelayMs > 0L) {
            SystemClock.sleep(preDelayMs);
        }

        float minConfidence = parseFloat(inputMap == null ? null : inputMap.get(MetaOperation.AI_MIN_CONFIDENCE),
                0.35f, 0.01f, 0.99f);
        float iouThreshold = parseFloat(inputMap == null ? null : inputMap.get(MetaOperation.AI_IOU_THRESHOLD),
                0.45f, 0.01f, 0.99f);
        int maxResults = parseInt(inputMap == null ? null : inputMap.get(MetaOperation.AI_MAX_RESULTS),
                5, 1, 50);
        int fallbackInputSize = parseInt(inputMap == null ? null : inputMap.get(MetaOperation.AI_INPUT_SIZE),
                640, 32, 2048);
        String targetLabel = getString(inputMap, MetaOperation.AI_TARGET_LABEL, "");
        List<String> labels = loadLabels(resolveFile(appContext, getString(inputMap, MetaOperation.AI_LABELS_PATH, "")));

        Rect captureRoi = rectFromBbox(roi);
        Bitmap source = null;
        try {
            source = ScreenCapture.captureLatestBitmap(captureRoi, 1200L, 60L);
            if (source == null || source.isRecycled()) {
                putFailure(ctx, obj, "capture_failed", "截图失败");
                return true;
            }
            List<Detection> detections = runDetection(
                    modelFile,
                    source,
                    captureRoi.left,
                    captureRoi.top,
                    fallbackInputSize,
                    labels,
                    minConfidence,
                    iouThreshold,
                    maxResults,
                    targetLabel);
            Detection best = detections.isEmpty() ? null : detections.get(0);
            boolean matched = best != null;
            if (matched && !ctx.suppressVisualFeedback) {
                AutoAccessibilityService svc = AutoAccessibilityService.get();
                if (svc != null && getMainHandler() != null) {
                    getMainHandler().post(() -> svc.showRectFeedback(
                            best.x, best.y, best.w, best.h,
                            420, 0x00000000, 0, 0x6638BDF8));
                }
            }

            List<Map<String, Object>> responseDetections = new ArrayList<>();
            for (Detection detection : detections) {
                responseDetections.add(detection.toMap());
            }
            HashMap<String, Object> response = new HashMap<>();
            response.put(MetaOperation.MATCHED, matched);
            response.put(MetaOperation.AI_DETECTIONS, responseDetections);
            response.put(MetaOperation.RESULT, best == null ? "" : best.label);
            if (best != null) {
                response.put(MetaOperation.BBOX, best.bboxList());
                response.put(MetaOperation.AI_LABEL, best.label);
                response.put(MetaOperation.AI_CONFIDENCE, best.confidence);
                response.put(MetaOperation.AI_CENTER, best.centerList());
            }
            response.put("reason", matched ? "detected" : "not_found");
            response.put("modelPath", modelFile.getAbsolutePath());
            ctx.currentResponse = response;
            ctx.lastOperation = obj;
            ctx.currentOperation = obj;

            putConfiguredVariables(ctx, inputMap, best, responseDetections);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AI detect failed", e);
            putFailure(ctx, obj, "detect_failed", e.getMessage() == null ? "AI 检测失败" : e.getMessage());
            return true;
        } finally {
            if (source != null && !source.isRecycled()) {
                source.recycle();
            }
        }
    }

    private static void putConfiguredVariables(OperationContext ctx,
                                               Map<String, Object> inputMap,
                                               Detection best,
                                               List<Map<String, Object>> detections) {
        String resultVar = getString(inputMap, MetaOperation.AI_RESULT_VAR, "");
        String bboxVar = getString(inputMap, MetaOperation.AI_BBOX_VAR, "");
        String confidenceVar = getString(inputMap, MetaOperation.AI_CONFIDENCE_VAR, "");
        String labelVar = getString(inputMap, MetaOperation.AI_LABEL_VAR, "");
        String centerVar = getString(inputMap, MetaOperation.AI_CENTER_VAR, "");
        if (!TextUtils.isEmpty(resultVar)) {
            ctx.variables.put(resultVar, detections);
        }
        if (best == null) {
            return;
        }
        if (!TextUtils.isEmpty(bboxVar)) {
            ctx.variables.put(bboxVar, best.bboxList());
        }
        if (!TextUtils.isEmpty(confidenceVar)) {
            ctx.variables.put(confidenceVar, best.confidence);
        }
        if (!TextUtils.isEmpty(labelVar)) {
            ctx.variables.put(labelVar, best.label);
        }
        if (!TextUtils.isEmpty(centerVar)) {
            ctx.variables.put(centerVar, best.centerList());
        }
    }

    private static List<Detection> runDetection(File modelFile,
                                                Bitmap source,
                                                int offsetX,
                                                int offsetY,
                                                int fallbackInputSize,
                                                List<String> labels,
                                                float minConfidence,
                                                float iouThreshold,
                                                int maxResults,
                                                String targetLabel) throws Exception {
        synchronized (INTERPRETER_LOCK) {
            Interpreter interpreter = getOrCreateInterpreter(modelFile);
            Tensor inputTensor = interpreter.getInputTensor(0);
            int[] inputShape = inputTensor.shape();
            boolean nchw = inputShape.length == 4 && inputShape[1] == 3;
            int inputHeight = inputShape.length == 4
                    ? Math.max(1, nchw ? inputShape[2] : inputShape[1])
                    : fallbackInputSize;
            int inputWidth = inputShape.length == 4
                    ? Math.max(1, nchw ? inputShape[3] : inputShape[2])
                    : fallbackInputSize;
            if (inputHeight <= 1 || inputWidth <= 1) {
                inputHeight = fallbackInputSize;
                inputWidth = fallbackInputSize;
            }
            Bitmap resized = Bitmap.createScaledBitmap(source, inputWidth, inputHeight, true);
            try {
                ByteBuffer input = buildInputBuffer(resized, inputTensor.dataType(), nchw);
                int outputCount = interpreter.getOutputTensorCount();
                List<Detection> detections;
                if (outputCount >= 4) {
                    detections = runSsdOutputs(interpreter, input, source, offsetX, offsetY, labels, minConfidence, maxResults, targetLabel);
                } else {
                    detections = runYoloOutput(interpreter, input, source, offsetX, offsetY, inputWidth, inputHeight,
                            labels, minConfidence, iouThreshold, maxResults, targetLabel);
                }
                return detections;
            } finally {
                if (!resized.isRecycled()) {
                    resized.recycle();
                }
            }
        }
    }

    private static Interpreter getOrCreateInterpreter(File modelFile) throws Exception {
        String path = modelFile.getAbsolutePath();
        if (cachedInterpreter != null && TextUtils.equals(cachedModelPath, path)) {
            return cachedInterpreter;
        }
        if (cachedInterpreter != null) {
            cachedInterpreter.close();
            cachedInterpreter = null;
        }
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
        try (FileInputStream inputStream = new FileInputStream(modelFile);
             FileChannel channel = inputStream.getChannel()) {
            cachedInterpreter = new Interpreter(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()), options);
            cachedModelPath = path;
            return cachedInterpreter;
        }
    }

    private static ByteBuffer buildInputBuffer(Bitmap bitmap, DataType dataType, boolean nchw) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int bytesPerChannel = dataType == DataType.UINT8 ? 1 : 4;
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 3 * bytesPerChannel);
        buffer.order(ByteOrder.nativeOrder());
        if (dataType == DataType.UINT8) {
            if (nchw) {
                for (int c = 0; c < 3; c++) {
                    for (int pixel : pixels) {
                        buffer.put((byte) channelValue(pixel, c));
                    }
                }
            } else {
                for (int pixel : pixels) {
                    buffer.put((byte) ((pixel >> 16) & 0xFF));
                    buffer.put((byte) ((pixel >> 8) & 0xFF));
                    buffer.put((byte) (pixel & 0xFF));
                }
            }
        } else {
            if (nchw) {
                for (int c = 0; c < 3; c++) {
                    for (int pixel : pixels) {
                        buffer.putFloat(channelValue(pixel, c) / 255.0f);
                    }
                }
            } else {
                for (int pixel : pixels) {
                    buffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
                    buffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);
                    buffer.putFloat((pixel & 0xFF) / 255.0f);
                }
            }
        }
        buffer.rewind();
        return buffer;
    }

    private static int channelValue(int pixel, int channel) {
        if (channel == 0) return (pixel >> 16) & 0xFF;
        if (channel == 1) return (pixel >> 8) & 0xFF;
        return pixel & 0xFF;
    }

    private static List<Detection> runYoloOutput(Interpreter interpreter,
                                                 ByteBuffer input,
                                                 Bitmap source,
                                                 int offsetX,
                                                 int offsetY,
                                                 int inputWidth,
                                                 int inputHeight,
                                                 List<String> labels,
                                                 float minConfidence,
                                                 float iouThreshold,
                                                 int maxResults,
                                                 String targetLabel) {
        Tensor outputTensor = interpreter.getOutputTensor(0);
        if (outputTensor.dataType() != DataType.FLOAT32) {
            throw new IllegalArgumentException("暂只支持 FLOAT32 输出的 YOLO TFLite 模型");
        }
        int[] shape = outputTensor.shape();
        if (shape.length != 3) {
            throw new IllegalArgumentException("不支持的 YOLO 输出维度: " + java.util.Arrays.toString(shape));
        }
        float[][][] output = new float[shape[0]][shape[1]][shape[2]];
        interpreter.run(input, output);
        int dimA = shape[1];
        int dimB = shape[2];
        boolean transposed = dimA <= 256 && dimB > dimA;
        int count = transposed ? dimB : dimA;
        int attrs = transposed ? dimA : dimB;
        if (attrs < 5) {
            throw new IllegalArgumentException("YOLO 输出属性数量不足: " + attrs);
        }
        List<Detection> raw = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float cx = valueAt(output, transposed, i, 0);
            float cy = valueAt(output, transposed, i, 1);
            float w = valueAt(output, transposed, i, 2);
            float h = valueAt(output, transposed, i, 3);
            boolean hasObjectness = hasYoloObjectness(attrs, labels);
            int classStart = hasObjectness ? 5 : 4;
            float objectness = hasObjectness ? clamp01(valueAt(output, transposed, i, 4)) : 1f;
            int bestClass = 0;
            float bestScore = classStart >= attrs ? objectness : 0f;
            for (int c = classStart; c < attrs; c++) {
                float score = clamp01(valueAt(output, transposed, i, c));
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c - classStart;
                }
            }
            float confidence = objectness * bestScore;
            if (confidence < minConfidence) {
                continue;
            }
            Detection detection = convertYoloBox(cx, cy, w, h, source.getWidth(), source.getHeight(),
                    inputWidth, inputHeight, offsetX, offsetY, bestClass, labelFor(labels, bestClass), confidence);
            if (matchesTarget(detection.label, targetLabel)) {
                raw.add(detection);
            }
        }
        return nms(raw, iouThreshold, maxResults);
    }

    private static boolean hasYoloObjectness(int attrs, List<String> labels) {
        int labelCount = labels == null ? 0 : labels.size();
        if (labelCount > 0) {
            if (attrs == labelCount + 5) return true;
            if (attrs == labelCount + 4) return false;
        }
        return attrs == 5 || attrs == 85;
    }

    private static float valueAt(float[][][] output, boolean transposed, int index, int attr) {
        return transposed ? output[0][attr][index] : output[0][index][attr];
    }

    private static Detection convertYoloBox(float cx,
                                            float cy,
                                            float w,
                                            float h,
                                            int sourceWidth,
                                            int sourceHeight,
                                            int inputWidth,
                                            int inputHeight,
                                            int offsetX,
                                            int offsetY,
                                            int classIndex,
                                            String label,
                                            float confidence) {
        boolean normalized = Math.max(Math.max(Math.abs(cx), Math.abs(cy)), Math.max(Math.abs(w), Math.abs(h))) <= 2f;
        float scaleX = normalized ? sourceWidth : (sourceWidth / (float) inputWidth);
        float scaleY = normalized ? sourceHeight : (sourceHeight / (float) inputHeight);
        float left = (cx - w / 2f) * scaleX;
        float top = (cy - h / 2f) * scaleY;
        float boxW = w * scaleX;
        float boxH = h * scaleY;
        int x = offsetX + Math.max(0, Math.round(left));
        int y = offsetY + Math.max(0, Math.round(top));
        int right = offsetX + Math.min(sourceWidth, Math.round(left + boxW));
        int bottom = offsetY + Math.min(sourceHeight, Math.round(top + boxH));
        return new Detection(classIndex, label, confidence, x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }

    private static List<Detection> runSsdOutputs(Interpreter interpreter,
                                                 ByteBuffer input,
                                                 Bitmap source,
                                                 int offsetX,
                                                 int offsetY,
                                                 List<String> labels,
                                                 float minConfidence,
                                                 int maxResults,
                                                 String targetLabel) {
        int n = Math.max(1, interpreter.getOutputTensor(0).shape()[1]);
        float[][][] boxes = new float[1][n][4];
        float[][] classes = new float[1][n];
        float[][] scores = new float[1][n];
        float[] count = new float[1];
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, boxes);
        outputs.put(1, classes);
        outputs.put(2, scores);
        outputs.put(3, count);
        interpreter.runForMultipleInputsOutputs(new Object[]{input}, outputs);
        int total = Math.min(n, count[0] > 0 ? Math.round(count[0]) : n);
        List<Detection> detections = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            float confidence = scores[0][i];
            if (confidence < minConfidence) continue;
            int classIndex = Math.max(0, Math.round(classes[0][i]));
            String label = labelFor(labels, classIndex);
            if (!matchesTarget(label, targetLabel)) continue;
            float yMin = boxes[0][i][0];
            float xMin = boxes[0][i][1];
            float yMax = boxes[0][i][2];
            float xMax = boxes[0][i][3];
            int x = offsetX + Math.max(0, Math.round(xMin * source.getWidth()));
            int y = offsetY + Math.max(0, Math.round(yMin * source.getHeight()));
            int right = offsetX + Math.min(source.getWidth(), Math.round(xMax * source.getWidth()));
            int bottom = offsetY + Math.min(source.getHeight(), Math.round(yMax * source.getHeight()));
            detections.add(new Detection(classIndex, label, confidence, x, y, Math.max(1, right - x), Math.max(1, bottom - y)));
        }
        Collections.sort(detections, (a, b) -> Float.compare(b.confidence, a.confidence));
        return detections.size() > maxResults ? new ArrayList<>(detections.subList(0, maxResults)) : detections;
    }

    private static List<Detection> nms(List<Detection> detections, float iouThreshold, int maxResults) {
        detections.sort(Comparator.comparingDouble((Detection d) -> d.confidence).reversed());
        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : detections) {
            boolean overlap = false;
            for (Detection selected : kept) {
                if (candidate.classIndex == selected.classIndex && iou(candidate, selected) > iouThreshold) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) {
                kept.add(candidate);
                if (kept.size() >= maxResults) break;
            }
        }
        return kept;
    }

    private static float iou(Detection a, Detection b) {
        int left = Math.max(a.x, b.x);
        int top = Math.max(a.y, b.y);
        int right = Math.min(a.x + a.w, b.x + b.w);
        int bottom = Math.min(a.y + a.h, b.y + b.h);
        int inter = Math.max(0, right - left) * Math.max(0, bottom - top);
        int areaA = a.w * a.h;
        int areaB = b.w * b.h;
        return inter <= 0 ? 0f : inter / (float) (areaA + areaB - inter);
    }

    private static void putFailure(OperationContext ctx, MetaOperation operation, String reason, String message) {
        HashMap<String, Object> response = new HashMap<>();
        response.put(MetaOperation.MATCHED, false);
        response.put(MetaOperation.RESULT, "");
        response.put(MetaOperation.AI_DETECTIONS, new ArrayList<>());
        response.put("reason", reason);
        response.put("message", message == null ? "" : message);
        ctx.currentResponse = response;
        ctx.lastOperation = operation;
        ctx.currentOperation = operation;
    }

    private static Context getAppContext() {
        AutoAccessibilityService svc = AutoAccessibilityService.get();
        if (svc != null) return svc.getApplicationContext();
        return AtommApplication.instance == null ? null : AtommApplication.instance.getApplicationContext();
    }

    private static Rect rectFromBbox(List<Integer> bbox) {
        return new Rect(bbox.get(0), bbox.get(1), bbox.get(0) + bbox.get(2), bbox.get(1) + bbox.get(3));
    }

    private static List<Integer> parseBbox(Object raw) {
        if (raw instanceof List) {
            List<?> list = (List<?>) raw;
            if (list.size() >= 4) {
                List<Integer> result = new ArrayList<>(4);
                for (int i = 0; i < 4; i++) {
                    int value = parseInt(list.get(i), 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
                    result.add(value);
                }
                return result.get(2) > 0 && result.get(3) > 0 ? result : null;
            }
        }
        if (raw instanceof String) {
            String[] parts = ((String) raw).split(",");
            if (parts.length >= 4) {
                List<Integer> result = new ArrayList<>(4);
                for (int i = 0; i < 4; i++) {
                    result.add(parseInt(parts[i], 0, Integer.MIN_VALUE, Integer.MAX_VALUE));
                }
                return result.get(2) > 0 && result.get(3) > 0 ? result : null;
            }
        }
        return null;
    }

    private static File resolveFile(Context context, String rawPath) {
        if (context == null || TextUtils.isEmpty(rawPath)) return null;
        String path = rawPath.trim();
        File direct = new File(path);
        if (direct.isAbsolute()) return direct;
        File appRoot = AppStorage.getAppFilesRoot(context);
        File file = new File(appRoot, path);
        if (file.exists()) return file;
        File internal = new File(context.getFilesDir(), path);
        if (internal.exists()) return internal;
        File projects = ProjectDataUtil.getProjectsRoot(context);
        File projectFile = new File(projects, path);
        return projectFile.exists() ? projectFile : file;
    }

    private static List<String> loadLabels(File file) {
        if (file == null || !file.exists() || !file.isFile()) return new ArrayList<>();
        List<String> labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) labels.add(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "load labels failed: " + file.getAbsolutePath(), e);
        }
        return labels;
    }

    private static boolean matchesTarget(String label, String targetLabel) {
        return TextUtils.isEmpty(targetLabel)
                || TextUtils.equals(label, targetLabel.trim())
                || TextUtils.equals(label.toLowerCase(Locale.ROOT), targetLabel.trim().toLowerCase(Locale.ROOT));
    }

    private static String labelFor(List<String> labels, int index) {
        if (labels != null && index >= 0 && index < labels.size()) {
            return labels.get(index);
        }
        return "class_" + index;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String getString(Map<String, Object> inputMap, String key, String def) {
        if (inputMap == null || key == null) return def;
        Object value = inputMap.get(key);
        return value == null ? def : String.valueOf(value).trim();
    }

    private static int parseInt(Object raw, int def, int min, int max) {
        int value = def;
        if (raw instanceof Number) {
            value = ((Number) raw).intValue();
        } else if (raw instanceof String && !TextUtils.isEmpty((String) raw)) {
            try {
                value = Integer.parseInt(((String) raw).trim());
            } catch (Exception ignored) {
                value = def;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static long parseLong(Object raw, long def, long min, long max) {
        long value = def;
        if (raw instanceof Number) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof String && !TextUtils.isEmpty((String) raw)) {
            try {
                value = Long.parseLong(((String) raw).trim());
            } catch (Exception ignored) {
                value = def;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static float parseFloat(Object raw, float def, float min, float max) {
        float value = def;
        if (raw instanceof Number) {
            value = ((Number) raw).floatValue();
        } else if (raw instanceof String && !TextUtils.isEmpty((String) raw)) {
            try {
                value = Float.parseFloat(((String) raw).trim());
            } catch (Exception ignored) {
                value = def;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private static final class Detection {
        final int classIndex;
        final String label;
        final float confidence;
        final int x;
        final int y;
        final int w;
        final int h;

        Detection(int classIndex, String label, float confidence, int x, int y, int w, int h) {
            this.classIndex = classIndex;
            this.label = label == null ? "" : label;
            this.confidence = confidence;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        List<Integer> bboxList() {
            List<Integer> bbox = new ArrayList<>(4);
            bbox.add(x);
            bbox.add(y);
            bbox.add(w);
            bbox.add(h);
            return bbox;
        }

        List<Integer> centerList() {
            List<Integer> center = new ArrayList<>(2);
            center.add(x + w / 2);
            center.add(y + h / 2);
            return center;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("label", label);
            map.put("classIndex", classIndex);
            map.put("confidence", confidence);
            map.put("bbox", bboxList());
            map.put("center", centerList());
            return map;
        }
    }
}

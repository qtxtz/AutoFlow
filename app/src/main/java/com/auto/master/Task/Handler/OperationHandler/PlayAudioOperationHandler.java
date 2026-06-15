package com.auto.master.Task.Handler.OperationHandler;

import android.media.MediaPlayer;
import android.util.Log;

import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OperationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PlayAudioOperationHandler extends OperationHandler {

    private static final String TAG = "PlayAudioOp";
    private static volatile MediaPlayer currentPlayer;

    PlayAudioOperationHandler() {
        this.setType(28);
    }

    @Override
    public boolean handle(MetaOperation obj, OperationContext ctx) {
        Map<String, Object> inputMap = obj.getInputMap();
        String filePath = inputMap == null ? null : (String) inputMap.get(MetaOperation.AUDIO_FILE_PATH);
        boolean waitComplete = false;
        if (inputMap != null) {
            Object w = inputMap.get(MetaOperation.AUDIO_WAIT_COMPLETE);
            if (w instanceof Boolean) waitComplete = (Boolean) w;
            else if (w instanceof Number) waitComplete = ((Number) w).intValue() != 0;
            else if (w instanceof String) waitComplete = "true".equalsIgnoreCase(((String) w).trim());
        }

        if (filePath == null || filePath.trim().isEmpty()) {
            Log.w(TAG, "AUDIO_FILE_PATH 为空，跳过");
            buildResult(ctx, obj, false);
            return true;
        }
        filePath = filePath.trim();

        stopCurrent();

        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(filePath);
            player.prepare();

            if (waitComplete) {
                CountDownLatch latch = new CountDownLatch(1);
                player.setOnCompletionListener(mp -> latch.countDown());
                player.setOnErrorListener((mp, what, extra) -> {
                    latch.countDown();
                    return true;
                });
                currentPlayer = player;
                player.start();
                long duration = player.getDuration();
                long timeoutMs = Math.max(duration + 5000L, 60000L);
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                stopCurrent();
            } else {
                player.setOnCompletionListener(mp -> {
                    mp.release();
                    if (currentPlayer == mp) currentPlayer = null;
                });
                currentPlayer = player;
                player.start();
            }
            buildResult(ctx, obj, true);
        } catch (Exception e) {
            Log.e(TAG, "播放音频失败: " + filePath, e);
            stopCurrent();
            buildResult(ctx, obj, false);
        }
        return true;
    }

    private static void stopCurrent() {
        MediaPlayer old = currentPlayer;
        currentPlayer = null;
        if (old != null) {
            try {
                if (old.isPlaying()) old.stop();
                old.release();
            } catch (Exception ignored) {}
        }
    }

    private void buildResult(OperationContext ctx, MetaOperation obj, boolean success) {
        if (ctx != null) {
            Map<String, Object> res = new HashMap<>();
            res.put(MetaOperation.MATCHED, success);
            ctx.currentResponse = res;
            ctx.lastOperation = obj;
        }
    }
}

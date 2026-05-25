package com.auto.master.floatwin;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.auto.master.R;
import com.auto.master.Task.Operation.MetaOperation;

class NodePreDelayDialogHelper {

    interface OnConfirmListener {
        void onConfirm(long delayMs, long minMs, long maxMs, boolean random);
    }

    private final Context context;
    private final WindowManager wm;

    NodePreDelayDialogHelper(Context context, WindowManager wm) {
        this.context = context;
        this.wm = wm;
    }

    void show(long currentDelayMs,
              long currentMinMs,
              long currentMaxMs,
              boolean currentRandom,
              OnConfirmListener listener) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_node_pre_delay, null);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(360),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;

        EditText input = dialogView.findViewById(R.id.edt_node_pre_delay_ms);
        EditText minInput = dialogView.findViewById(R.id.edt_node_pre_delay_min_ms);
        EditText maxInput = dialogView.findViewById(R.id.edt_node_pre_delay_max_ms);
        View fixedSection = dialogView.findViewById(R.id.ly_node_pre_delay_fixed);
        View randomSection = dialogView.findViewById(R.id.ly_node_pre_delay_random);
        Switch randomSwitch = dialogView.findViewById(R.id.sw_node_pre_delay_random);
        input.setHint(String.valueOf(MetaOperation.DEFAULT_NODE_PRE_DELAY_MS));
        input.setText(String.valueOf(Math.max(0L, currentDelayMs)));
        minInput.setText(String.valueOf(Math.max(0L, currentMinMs)));
        maxInput.setText(String.valueOf(Math.max(0L, currentMaxMs)));
        randomSwitch.setChecked(currentRandom);
        updateModeVisibility(fixedSection, randomSection, currentRandom);
        randomSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                updateModeVisibility(fixedSection, randomSection, isChecked));

        dialogView.findViewById(R.id.btn_delay_cancel).setOnClickListener(v -> safeRemove(dialogView));
        dialogView.findViewById(R.id.btn_delay_confirm).setOnClickListener(v -> {
            boolean random = randomSwitch.isChecked();
            long delayMs = MetaOperation.DEFAULT_NODE_PRE_DELAY_MS;
            long minMs = 0L;
            long maxMs = 0L;
            if (random) {
                try {
                    minMs = parseDelayInput(minInput);
                    maxMs = parseDelayInput(maxInput);
                } catch (Exception e) {
                    minInput.setError("请输入毫秒数");
                    return;
                }
                if (maxMs < minMs) {
                    maxInput.setError("最大值不能小于最小值");
                    return;
                }
                maxMs = Math.min(maxMs, MetaOperation.MAX_NODE_PRE_DELAY_MS);
                minMs = Math.min(minMs, maxMs);
                delayMs = maxMs;
            } else {
                try {
                    delayMs = parseDelayInput(input);
                } catch (Exception e) {
                    input.setError("请输入毫秒数");
                    return;
                }
                minMs = 0L;
                maxMs = delayMs;
            }
            if (listener != null) {
                listener.onConfirm(delayMs, minMs, maxMs, random);
            }
            safeRemove(dialogView);
        });

        try {
            wm.addView(dialogView, lp);
            input.requestFocus();
        } catch (Exception e) {
            Toast.makeText(context, "打开延迟设置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void safeRemove(View view) {
        try {
            if (view != null && view.getParent() != null) {
                wm.removeView(view);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateModeVisibility(View fixedSection, View randomSection, boolean random) {
        if (fixedSection != null) {
            fixedSection.setVisibility(random ? View.GONE : View.VISIBLE);
        }
        if (randomSection != null) {
            randomSection.setVisibility(random ? View.VISIBLE : View.GONE);
        }
    }

    private long parseDelayInput(EditText input) {
        String text = input == null || input.getText() == null ? "" : input.getText().toString().trim();
        long value = TextUtils.isEmpty(text) ? MetaOperation.DEFAULT_NODE_PRE_DELAY_MS : Long.parseLong(text);
        if (value < 0L) {
            throw new IllegalArgumentException("negative");
        }
        return Math.min(value, MetaOperation.MAX_NODE_PRE_DELAY_MS);
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}

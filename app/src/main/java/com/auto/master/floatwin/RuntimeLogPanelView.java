package com.auto.master.floatwin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

final class RuntimeLogPanelView extends LinearLayout {

    private final TextView logText;
    private final ScrollView scrollView;
    private final View headerView;
    private final View resizeHandle;
    private Runnable closeListener;
    private Runnable clearListener;

    RuntimeLogPanelView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(10), dp(8), dp(10), dp(8));
        setBackground(buildBackground());
        setAlpha(0.88f);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(6));
        headerView = header;

        TextView title = new TextView(context);
        title.setText("运行日志");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = buildActionText("清空");
        clear.setOnClickListener(v -> {
            if (clearListener != null) clearListener.run();
        });
        header.addView(clear);

        TextView close = buildActionText("×");
        close.setTextSize(20);
        close.setOnClickListener(v -> {
            if (closeListener != null) closeListener.run();
        });
        header.addView(close);

        addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        logText = new TextView(context);
        logText.setTextColor(0xFFE5E7EB);
        logText.setTextSize(11);
        logText.setLineSpacing(0, 1.08f);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(false);
        logText.setText("暂无日志");
        scrollView.addView(logText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        addView(scrollView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        TextView handle = new TextView(context);
        handle.setText("↘");
        handle.setTextColor(0xFFCBD5E1);
        handle.setTextSize(16);
        handle.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        handle.setPadding(0, dp(2), dp(2), 0);
        resizeHandle = handle;
        addView(handle, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(24)));
    }

    View getHeaderView() {
        return headerView;
    }

    View getResizeHandle() {
        return resizeHandle;
    }

    void setOnClose(Runnable listener) {
        closeListener = listener;
    }

    void setOnClear(Runnable listener) {
        clearListener = listener;
    }

    void setLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            logText.setText("暂无日志");
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : lines) {
            if (!TextUtils.isEmpty(line)) {
                appendColoredLine(sb, line);
            }
        }
        logText.setText(sb.length() == 0 ? "暂无日志" : sb);
        scrollToBottom();
    }

    void appendLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return;
        }
        CharSequence current = logText.getText();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (current != null && !"暂无日志".contentEquals(current)) {
            sb.append(current);
        }
        appendColoredLine(sb, line);
        logText.setText(sb);
        scrollToBottom();
    }

    private void appendColoredLine(SpannableStringBuilder sb, String line) {
        int start = sb.length();
        sb.append(line).append('\n');
        int color = resolveLineColor(line);
        if (color != 0) {
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private int resolveLineColor(String line) {
        if (line == null) {
            return 0xFFE5E7EB;
        }
        String upper = line.toUpperCase();
        if (upper.contains("ERROR") || upper.contains("失败") || upper.contains("SUCCESS=FALSE")) {
            return 0xFFFCA5A5;
        }
        if (upper.contains("WARN")) {
            return 0xFFFDE68A;
        }
        if (line.contains("[start]")) {
            return 0xFF93C5FD;
        }
        if (line.contains("[done]")) {
            return 0xFF86EFAC;
        }
        if (upper.contains("[INFO]")) {
            return 0xFF67E8F9;
        }
        if (upper.contains("[DEBUG]")) {
            return 0xFFC4B5FD;
        }
        if ((upper.contains("命中") && !upper.contains("未命中")) || upper.contains("成功")) {
            return 0xFFA7F3D0;
        }
        return 0xFFE5E7EB;
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(FOCUS_DOWN));
    }

    private TextView buildActionText(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        return tv;
    }

    private GradientDrawable buildBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC111827);
        bg.setStroke(dp(1), 0xAA38BDF8);
        bg.setCornerRadius(dp(8));
        return bg;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

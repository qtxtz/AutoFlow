package com.auto.master.Task.Handler.OperationHandler;

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.auto.master.Task.Operation.AccessibilityNodeOperation;
import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OperationContext;
import com.auto.master.auto.AutoAccessibilityService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 无障碍节点操作处理器。
 *
 * 查找模式 (A11Y_FIND_MODE):
 *   text             - 节点文字精确匹配
 *   textContains     - 节点文字包含
 *   textStartsWith   - 节点文字开头
 *   viewId           - 资源 ID（如 com.pkg:id/btn_ok）
 *   contentDesc      - ContentDescription 精确匹配
 *   contentDescContains - ContentDescription 包含
 *   className        - 类名（如 android.widget.Button）
 *
 * 动作 (A11Y_ACTION):
 *   click            - 点击节点
 *   longClick        - 长按节点
 *   getText          - 读取文字，存入 A11Y_RESULT_VAR
 *   getDesc          - 读取 ContentDescription，存入 A11Y_RESULT_VAR
 *   setText          - 输入文字（A11Y_ACTION_TEXT），需 API 21+
 *   scrollUp         - 向上滚动
 *   scrollDown       - 向下滚动
 *   focus            - 请求焦点
 *   exists           - 仅判断节点是否存在（不执行动作）
 *
 * 路由规则：
 *   MATCHED=true  → 节点存在且动作成功 → 跳转 NEXT_OPERATION_ID
 *   MATCHED=false → 节点不存在或动作失败 → 跳转 FALLBACKOPERATIONID
 *
 * handle() 始终返回 true，路由由 ColorMatchResponseHandler 决定。
 */
class AccessibilityNodeOperationHandler extends OperationHandler {

    private static final String TAG = "A11yNodeHandler";
    private static final long DEFAULT_TIMEOUT_MS = 0L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 200L;
    private static final long MAX_TIMEOUT_MS = 30_000L;

    // 动作常量
    static final String ACTION_CLICK = "click";
    static final String ACTION_LONG_CLICK = "longClick";
    static final String ACTION_GET_TEXT = "getText";
    static final String ACTION_GET_DESC = "getDesc";
    static final String ACTION_SET_TEXT = "setText";
    static final String ACTION_SCROLL_UP = "scrollUp";
    static final String ACTION_SCROLL_DOWN = "scrollDown";
    static final String ACTION_FOCUS = "focus";
    static final String ACTION_EXISTS = "exists";

    // 查找模式常量
    static final String FIND_TEXT = "text";
    static final String FIND_TEXT_CONTAINS = "textContains";
    static final String FIND_TEXT_STARTS = "textStartsWith";
    static final String FIND_VIEW_ID = "viewId";
    static final String FIND_CONTENT_DESC = "contentDesc";
    static final String FIND_CONTENT_DESC_CONTAINS = "contentDescContains";
    static final String FIND_CLASS_NAME = "className";

    AccessibilityNodeOperationHandler() {
        this.setType(24);
    }

    @Override
    public boolean handle(MetaOperation obj, OperationContext ctx) {
        if (ctx == null) return false;
        if (ctx.variables == null) ctx.variables = new HashMap<>();

        Map<String, Object> inputMap = obj.getInputMap();
        if (inputMap == null) {
            putFailureResponse(ctx, obj, "inputMap 为空");
            return true;
        }

        AutoAccessibilityService svc = AutoAccessibilityService.get();
        if (svc == null) {
            Log.e(TAG, "无障碍服务未连接");
            putFailureResponse(ctx, obj, "无障碍服务未连接");
            return true;
        }

        // ——— 读取参数 ———
        String findMode    = VariableRuntimeUtils.getString(inputMap, MetaOperation.A11Y_FIND_MODE, FIND_TEXT).trim();
        String rawValue    = VariableRuntimeUtils.getString(inputMap, MetaOperation.A11Y_FIND_VALUE, "");
        String pkgFilter   = VariableRuntimeUtils.getString(inputMap, MetaOperation.A11Y_PACKAGE_FILTER, "").trim();
        String action      = VariableRuntimeUtils.getString(inputMap, MetaOperation.A11Y_ACTION, ACTION_CLICK).trim();
        String rawActText  = VariableRuntimeUtils.getString(inputMap, MetaOperation.A11Y_ACTION_TEXT, "");
        String resultVar   = VariableRuntimeUtils.getString(inputMap, MetaOperation.A11Y_RESULT_VAR, "").trim();
        int    matchIndex  = 0;
        long   timeoutMs   = DEFAULT_TIMEOUT_MS;
        long   pollMs      = DEFAULT_POLL_INTERVAL_MS;
        boolean scrollIntoView = false;

        Object rawIndex = inputMap.get(MetaOperation.A11Y_MATCH_INDEX);
        if (rawIndex instanceof Number) matchIndex = ((Number) rawIndex).intValue();
        else if (rawIndex instanceof String) {
            try { matchIndex = Integer.parseInt(((String) rawIndex).trim()); } catch (Exception ignored) {}
        }

        Object rawTimeout = inputMap.get(MetaOperation.A11Y_TIMEOUT_MS);
        if (rawTimeout instanceof Number) timeoutMs = ((Number) rawTimeout).longValue();
        else if (rawTimeout instanceof String) {
            try { timeoutMs = Long.parseLong(((String) rawTimeout).trim()); } catch (Exception ignored) {}
        }
        timeoutMs = Math.min(Math.max(0, timeoutMs), MAX_TIMEOUT_MS);

        Object rawPoll = inputMap.get(MetaOperation.A11Y_POLL_INTERVAL_MS);
        if (rawPoll instanceof Number) pollMs = ((Number) rawPoll).longValue();
        else if (rawPoll instanceof String) {
            try { pollMs = Long.parseLong(((String) rawPoll).trim()); } catch (Exception ignored) {}
        }
        pollMs = Math.max(50, Math.min(pollMs, 2000));

        Object rawScroll = inputMap.get(MetaOperation.A11Y_SCROLL_INTO_VIEW);
        if (rawScroll instanceof Boolean) scrollIntoView = (Boolean) rawScroll;
        else if (rawScroll instanceof String) scrollIntoView = "true".equalsIgnoreCase(((String) rawScroll).trim());

        // 变量替换
        String findValue = VariableRuntimeUtils.applyTemplate(rawValue, ctx.variables);
        String actionText = VariableRuntimeUtils.applyTemplate(rawActText, ctx.variables);

        if (TextUtils.isEmpty(findValue) && !findMode.equals(FIND_CLASS_NAME)) {
            putFailureResponse(ctx, obj, "查找值为空");
            return true;
        }

        // ——— 等待并查找节点 ———
        AccessibilityNodeInfo target = null;
        long deadline = System.currentTimeMillis() + timeoutMs;

        do {
            AccessibilityNodeInfo root = svc.getRootInActiveWindow();
            if (root != null) {
                try {
                    List<AccessibilityNodeInfo> candidates = findNodes(root, findMode, findValue, pkgFilter);
                    if (!candidates.isEmpty()) {
                        int safeIndex = Math.max(0, Math.min(matchIndex, candidates.size() - 1));
                        target = candidates.get(safeIndex);
                        // 释放其他候选引用
                        for (int i = 0; i < candidates.size(); i++) {
                            if (i != safeIndex) candidates.get(i).recycle();
                        }
                        break;
                    }
                } finally {
                    root.recycle();
                }
            }
            if (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(pollMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } while (System.currentTimeMillis() < deadline);

        if (target == null) {
            Log.d(TAG, "未找到节点 mode=" + findMode + " value=" + findValue);
            putFailureResponse(ctx, obj, "节点未找到");
            return true;
        }

        // ——— 执行动作 ———
        boolean success = false;
        String resultText = "";
        try {
            if (scrollIntoView && !ACTION_EXISTS.equals(action)) {
                scrollNodeIntoView(target);
            }

            switch (action) {
                case ACTION_CLICK:
                    success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    break;

                case ACTION_LONG_CLICK:
                    success = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                    break;

                case ACTION_GET_TEXT:
                    CharSequence text = target.getText();
                    resultText = text != null ? text.toString() : "";
                    if (!TextUtils.isEmpty(resultVar)) ctx.variables.put(resultVar, resultText);
                    success = true;
                    break;

                case ACTION_GET_DESC:
                    CharSequence desc = target.getContentDescription();
                    resultText = desc != null ? desc.toString() : "";
                    if (!TextUtils.isEmpty(resultVar)) ctx.variables.put(resultVar, resultText);
                    success = true;
                    break;

                case ACTION_SET_TEXT:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, actionText);
                        success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    } else {
                        // API < 21 兜底：先点击获得焦点，再通过剪贴板粘贴
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("text", actionText));
                            success = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                        }
                    }
                    break;

                case ACTION_SCROLL_UP:
                    success = performScrollOnSelfOrParent(target, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                    break;

                case ACTION_SCROLL_DOWN:
                    success = performScrollOnSelfOrParent(target, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    break;

                case ACTION_FOCUS:
                    success = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                    break;

                case ACTION_EXISTS:
                    // 节点已找到即为成功
                    success = true;
                    break;

                default:
                    Log.w(TAG, "未知动作: " + action);
                    success = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "执行节点动作失败 action=" + action, e);
            success = false;
        } finally {
            try { target.recycle(); } catch (Exception ignored) {}
        }

        HashMap<String, Object> response = new HashMap<>();
        response.put(MetaOperation.MATCHED, success);
        response.put(MetaOperation.RESULT, resultText);
        response.put("a11y_result_text", resultText);
        response.put("a11y_action_success", success);
        ctx.currentResponse = response;
        ctx.lastOperation   = obj;
        ctx.currentOperation = obj;

        Log.d(TAG, "节点操作完成 action=" + action + " success=" + success + " result=" + resultText);
        return true;
    }

    // ——— 节点查找 ———

    private List<AccessibilityNodeInfo> findNodes(AccessibilityNodeInfo root,
                                                   String findMode,
                                                   String findValue,
                                                   String pkgFilter) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        switch (findMode) {
            case FIND_TEXT:
                addAll(results, root.findAccessibilityNodeInfosByText(findValue));
                // findByText 是包含匹配，需过滤为精确匹配
                filterExactText(results, findValue);
                break;
            case FIND_TEXT_CONTAINS:
                addAll(results, root.findAccessibilityNodeInfosByText(findValue));
                break;
            case FIND_TEXT_STARTS:
                addAll(results, root.findAccessibilityNodeInfosByText(findValue));
                filterTextStartsWith(results, findValue);
                break;
            case FIND_VIEW_ID:
                addAll(results, root.findAccessibilityNodeInfosByViewId(findValue));
                break;
            case FIND_CONTENT_DESC:
                traverseForContentDesc(root, findValue, false, results);
                break;
            case FIND_CONTENT_DESC_CONTAINS:
                traverseForContentDesc(root, findValue, true, results);
                break;
            case FIND_CLASS_NAME:
                traverseForClassName(root, findValue, results);
                break;
            default:
                addAll(results, root.findAccessibilityNodeInfosByText(findValue));
        }
        if (!TextUtils.isEmpty(pkgFilter)) {
            filterByPackage(results, pkgFilter);
        }
        return results;
    }

    private void addAll(List<AccessibilityNodeInfo> dest, List<AccessibilityNodeInfo> src) {
        if (src != null) dest.addAll(src);
    }

    private void filterExactText(List<AccessibilityNodeInfo> nodes, String value) {
        List<AccessibilityNodeInfo> toRemove = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence t = n.getText();
            if (t == null || !t.toString().equals(value)) {
                toRemove.add(n);
            }
        }
        for (AccessibilityNodeInfo n : toRemove) {
            nodes.remove(n);
            n.recycle();
        }
    }

    private void filterTextStartsWith(List<AccessibilityNodeInfo> nodes, String value) {
        List<AccessibilityNodeInfo> toRemove = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence t = n.getText();
            if (t == null || !t.toString().startsWith(value)) {
                toRemove.add(n);
            }
        }
        for (AccessibilityNodeInfo n : toRemove) {
            nodes.remove(n);
            n.recycle();
        }
    }

    private void filterByPackage(List<AccessibilityNodeInfo> nodes, String pkg) {
        List<AccessibilityNodeInfo> toRemove = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence np = n.getPackageName();
            if (np == null || !np.toString().equals(pkg)) {
                toRemove.add(n);
            }
        }
        for (AccessibilityNodeInfo n : toRemove) {
            nodes.remove(n);
            n.recycle();
        }
    }

    private void traverseForContentDesc(AccessibilityNodeInfo node, String value, boolean contains,
                                         List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString();
            boolean match = contains ? d.contains(value) : d.equals(value);
            if (match) {
                results.add(AccessibilityNodeInfo.obtain(node));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                traverseForContentDesc(child, value, contains, results);
                child.recycle();
            }
        }
    }

    private void traverseForClassName(AccessibilityNodeInfo node, String className,
                                       List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        CharSequence cn = node.getClassName();
        if (cn != null && cn.toString().equals(className)) {
            results.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                traverseForClassName(child, className, results);
                child.recycle();
            }
        }
    }

    // ——— 辅助动作 ———

    private void scrollNodeIntoView(AccessibilityNodeInfo node) {
        // 先请求焦点，让可滚动的父容器将节点滚入可视区域
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        } catch (Exception ignored) {}
    }

    /**
     * 对节点本身或其可滚动父节点执行滚动动作。
     * 部分列表的列表项本身不可滚动，需要找父容器。
     */
    private boolean performScrollOnSelfOrParent(AccessibilityNodeInfo node, int scrollAction) {
        if (node == null) return false;
        if (node.isScrollable()) {
            return node.performAction(scrollAction);
        }
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isScrollable()) {
                boolean ok = parent.performAction(scrollAction);
                parent.recycle();
                return ok;
            }
            AccessibilityNodeInfo grandParent = parent.getParent();
            parent.recycle();
            parent = grandParent;
        }
        return false;
    }

    private void putFailureResponse(OperationContext ctx, MetaOperation obj, String reason) {
        HashMap<String, Object> response = new HashMap<>();
        response.put(MetaOperation.MATCHED, false);
        response.put(MetaOperation.RESULT, "");
        response.put("a11y_result_text", "");
        response.put("a11y_action_success", false);
        response.put("a11y_fail_reason", reason == null ? "" : reason);
        ctx.currentResponse = response;
        ctx.lastOperation   = obj;
        ctx.currentOperation = obj;
    }
}

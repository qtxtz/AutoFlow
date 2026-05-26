package com.auto.master.Task.Handler.ResponseHandler;


import android.os.SystemClock;
import android.util.Log;


import com.auto.master.Task.Handler.OperationHandler.OperationHandler;
import com.auto.master.Task.Handler.OperationHandler.OperationHandlerManager;
import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OperationContext;
import com.auto.master.Task.Project.Project;
import com.auto.master.Task.Task;
import com.auto.master.Task.model.Point;
import com.auto.master.auto.AutoAccessibilityService;
import com.auto.master.auto.ScriptExecuteContext;
import com.auto.master.utils.MatchResult;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

//@TYPE = 1   用于 模板匹配专用
public class MatchTemplateDynamicJumpResponseHandler extends DefaultResponseHandler {

    private static final String TAG = "MatchTemplateDynJump";
    private static final int MAX_CLICK_RETRY_COUNT = 5;
    private static final long CLICK_DISPATCH_TIMEOUT_MS = 250L;
    private static final long DEFAULT_CLICK_WAIT_TIMEOUT_MS = 4000L;

    public MatchTemplateDynamicJumpResponseHandler() {
        this.type = 1;
    }

    private static class ClickResult {
        final Object lock = new Object();
        boolean dispatched = false;
        boolean accepted = false;
        boolean completed = false;
        boolean ok = false;
    }


    /**
     * 在 BBox 区域内生成一个随机点
     *
     * @param bbox BBox 列表，格式为 [x, y, width, height]
     * @return 随机点 [randomX, randomY]，如果 BBox 无效返回 null
     */
    public List<Integer> generateRandomPointInBBox(List<Integer> bbox) {
        // 参数校验
        if (bbox == null || bbox.size() != 4) {
            Log.e(TAG, "无效的 BBox 参数");
            return null;
        }

        try {
            int x = bbox.get(0);
            int y = bbox.get(1);
            int width = bbox.get(2);
            int height = bbox.get(3);

            // 检查 BBox 是否有效
            if (width <= 0 || height <= 0) {
                Log.e(TAG, "BBox 宽度或高度无效: " + width + "x" + height);
                return null;
            }

            // 确保坐标非负
            x = Math.max(0, x);
            y = Math.max(0, y);
            width = Math.max(1, width);  // 最小宽度为1
            height = Math.max(1, height); // 最小高度为1

            // 生成随机坐标（ThreadLocalRandom 无竞争，无需每次构造 Random 对象）
            int randomX = x + ThreadLocalRandom.current().nextInt(width);   // x ~ x+width-1
            int randomY = y + ThreadLocalRandom.current().nextInt(height);  // y ~ y+height-1

            // 返回结果
            List<Integer> randomPoint = new ArrayList<>();
            randomPoint.add(randomX);
            randomPoint.add(randomY);

            Log.d(TAG, String.format("在 BBox [%d,%d,%d,%d] 内生成随机点: (%d,%d)",
                    x, y, width, height, randomX, randomY));

            return randomPoint;

        } catch (Exception e) {
            Log.e(TAG, "生成随机点失败", e);
            return null;
        }
    }

    @Override
    public void process(Object response, ScriptExecuteContext scriptExecuteContext) {
        if (scriptExecuteContext == null || scriptExecuteContext.sharedContext == null) {
            return;
        }
        // 默认终止，只有找到有效下一节点才覆盖
        scriptExecuteContext.tobeHandledOperation = null;

//        这种机制是：
//        如果匹配结果有效，则点击或者不惦记，跳转到下一个节点
//        如果匹配结果无效，则跳转某个节点 fallBack。
        OperationContext ctx = scriptExecuteContext.sharedContext;
        if (ctx.currentOperation == null || ctx.lastOperation == null) {
            return;
        }
//        对应 matchTemplate 的responsetype =1
        Map<String, Object> inputMap = ctx.currentOperation.getInputMap();
        if (inputMap == null) {
            return;
        }
        // 每种responseHandler只处理一种response
        String targetId = null;
        if (response instanceof Map) {
            Map result = (Map) response;
            if (result == null) {
//                没有结果 跳转 fallbackOperation 根据 fallbackOperationId

                Object o = inputMap.get(MetaOperation.FALLBACKOPERATIONID);
                String fallBackId = (String) o;
                if (!StringUtils.isNotEmpty(fallBackId)) {
                    Log.w(TAG, "匹配失败且 fallback 为空，终止脚本");
                    return;
                }
                targetId = fallBackId;
            } else {
                AutoAccessibilityService svc = AutoAccessibilityService.get();
                if (svc == null) return;
                Map<String, Object> currentResponse = ctx.currentResponse;
                Object o = currentResponse.get(MetaOperation.RESULT);
                if (o == null) {

                    Object o1 = inputMap.get(MetaOperation.FALLBACKOPERATIONID);
                    String fallBackId = (String) o1;
                    if (!StringUtils.isNotEmpty(fallBackId)) {
                        Log.w(TAG, "匹配结果为空且 fallback 为空，终止脚本");
                        return;
                    }
                    targetId = fallBackId;
                } else {
                    Boolean click = false;
//                    MatchResult matchResult = (MatchResult) o;
//                    找到点了 就点击bbox todo  这里是否点击从 inputMap里面看
                    Object o1 = inputMap.get(MetaOperation.SUCCEESCLICK);
                    Boolean o2 = (Boolean) o1;
                    if (o2 != null) {
                        click = o2;
                    }
                    if (click) {
                        Object box = ctx.currentResponse.get(MetaOperation.BBOX);
                        List<Integer> bbox = (List<Integer>) box;
//                    点击bbox里面任意一点
                        List<Integer> integers = generateRandomPointInBBox(bbox);
//                    List<Integer> integers = bbox;
                        Point target;
                        if (integers != null && !integers.isEmpty()) {
                            Integer x = integers.get(0);
                            Integer y = integers.get(1);
                            target = new Point(x, y);
                        } else if (bbox != null && bbox.size() >= 2) {
                            target = new Point(bbox.get(0), bbox.get(1));
                        } else {
                            targetId = getString(inputMap.get(MetaOperation.FALLBACKOPERATIONID));
                            Log.w(TAG, "点击区域无效，走 fallback: " + targetId);
                            if (!StringUtils.isNotEmpty(targetId)) {
                                return;
                            }
                            target = null;
                        }
                        //        先画区域
                        if (target != null && !ctx.suppressVisualFeedback) {
                            MAIN_HANDLER.post(() -> {
                                svc.showClickFeedback((int) target.x, (int) target.y, 280);
                            });
                        }
                        if (target != null && !performClickWithRetry(svc, target, inputMap)) {
                            targetId = getString(inputMap.get(MetaOperation.FALLBACKOPERATIONID));
                            Log.w(TAG, "匹配成功但点击失败，走 fallback: " + targetId);
                            if (!StringUtils.isNotEmpty(targetId)) {
                                return;
                            }
                        }
                    }


                }


            }


//            todo 不校验这个result的内容了
//            直接跳到下一步即可
            String nextOpId = (String) inputMap.get(MetaOperation.NEXT_OPERATION_ID);
            if (StringUtils.isNotEmpty(targetId)) {
//                todo 这里很重要
                nextOpId = targetId;
            }
            if (!StringUtils.isNotEmpty(nextOpId)) {
                /*
            不能把cpu打满 这里延时 10ms
             */
                SystemClock.sleep(10);
                return;
            }
            long postDelayMs = parseDelayMs(inputMap.get(MetaOperation.MATCH_POST_DELAY_MS));
            if (postDelayMs > 0) {
                SystemClock.sleep(postDelayMs);
            }
            // 跳转下一个operation 注意这里是 隶属的 task里面的 没有跳转 task 所以注意这个reponseHandler

            Project anchorProject = ctx.anchorProject;
            if (anchorProject == null || anchorProject.getTaskMap() == null) {
                return;
            }
            Map<String, Task> taskMap = anchorProject.getTaskMap();
            Task task = taskMap.get(ctx.lastOperation.taskId);
            if (task == null) {
                task = resolveTaskByOperationId(taskMap, ctx.lastOperation.getId());
            }
            if (task == null || task.getOperationMap() == null) {
                return;
            }

//            todo 这里有问题  这里 拿到下一个operation 然后 处理了 这个 operation 但是结果并没有自动处理！！！
//            怎么做呢
//            boolean notEnd = true;

            MetaOperation metaOperation = task.getOperationMap().get(nextOpId);
            if (metaOperation == null) {
                Log.e(TAG, "fallback/next 节点不存在，终止脚本: " + nextOpId);
                return;
            }

            scriptExecuteContext.tobeHandledOperation = metaOperation;
            /*
            不能把cpu打满 这里延时 10ms
             */
            SystemClock.sleep(10);


        }
        // 非 Map 响应直接终止，避免沿用旧节点造成假死

    }

    private long parseDelayMs(Object raw) {
        if (raw instanceof Number) {
            long v = ((Number) raw).longValue();
            return Math.max(0, Math.min(v, MetaOperation.MAX_MATCH_DELAY_MS));
        }
        if (raw instanceof String) {
            try {
                long v = Long.parseLong(((String) raw).trim());
                return Math.max(0, Math.min(v, MetaOperation.MAX_MATCH_DELAY_MS));
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private boolean performClickWithRetry(AutoAccessibilityService svc, Point target, Map<String, Object> inputMap) {
        ClickResult clickResult = new ClickResult();
        MAIN_HANDLER.post(() -> {
            boolean accepted = svc.clickWithRetry((int) target.x, (int) target.y,
                    () -> {
                        notifyClickCompletion(clickResult, true);
                        Log.d(TAG, "点击成功回调");
                    },
                    () -> {
                        notifyClickCompletion(clickResult, false);
                        Log.w(TAG, "点击失败回调");
                    },
                    MAX_CLICK_RETRY_COUNT);
            notifyClickDispatch(clickResult, accepted);
        });

        if (!waitForClickDispatch(clickResult, CLICK_DISPATCH_TIMEOUT_MS) || !clickResult.accepted) {
            return false;
        }
        long timeoutMs = positiveLong(inputMap.get(MetaOperation.CLICK_WAIT_TIMEOUT_MS), DEFAULT_CLICK_WAIT_TIMEOUT_MS);
        return waitForClickCompletion(clickResult, timeoutMs) && clickResult.ok;
    }

    private void notifyClickDispatch(ClickResult result, boolean accepted) {
        synchronized (result.lock) {
            if (result.dispatched) {
                return;
            }
            result.dispatched = true;
            result.accepted = accepted;
            result.lock.notifyAll();
        }
    }

    private void notifyClickCompletion(ClickResult result, boolean success) {
        synchronized (result.lock) {
            if (result.completed) {
                return;
            }
            result.completed = true;
            result.ok = success;
            result.lock.notifyAll();
        }
    }

    private boolean waitForClickDispatch(ClickResult result, long timeoutMs) {
        long start = System.currentTimeMillis();
        synchronized (result.lock) {
            while (!result.dispatched) {
                if (!waitOnClickResult(result, timeoutMs, start)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean waitForClickCompletion(ClickResult result, long timeoutMs) {
        long start = System.currentTimeMillis();
        synchronized (result.lock) {
            while (!result.completed) {
                if (!waitOnClickResult(result, timeoutMs, start)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean waitOnClickResult(ClickResult result, long timeoutMs, long start) {
        if (Thread.currentThread().isInterrupted()) {
            Log.d(TAG, "点击等待被中断");
            return false;
        }
        long left = timeoutMs - (System.currentTimeMillis() - start);
        if (left <= 0) {
            return false;
        }
        try {
            result.lock.wait(left);
            return true;
        } catch (InterruptedException e) {
            Log.d(TAG, "点击等待被中断", e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long positiveLong(Object raw, long fallback) {
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            return value > 0 ? value : fallback;
        }
        if (raw instanceof String) {
            try {
                long value = Long.parseLong(((String) raw).trim());
                return value > 0 ? value : fallback;
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private String getString(Object raw) {
        return raw == null ? null : String.valueOf(raw).trim();
    }
}

package com.auto.master.Task.Handler.OperationHandler;

import android.text.TextUtils;

import com.auto.master.Task.Operation.MetaOperation;
import com.auto.master.Task.Operation.OperationContext;
import com.auto.master.Task.Operation.OperationType;

import java.util.HashMap;
import java.util.Map;

public class LogOutputOperationHandler extends OperationHandler {

    LogOutputOperationHandler() {
        this.setType(OperationType.LOG_OUTPUT.getCode());
    }

    @Override
    public boolean handle(MetaOperation obj, OperationContext ctx) {
        Map<String, Object> inputMap = obj == null ? null : obj.getInputMap();
        String raw = inputMap == null ? "" : toString(inputMap.get(MetaOperation.LOG_MESSAGE));
        String level = inputMap == null ? "INFO" : toString(inputMap.get(MetaOperation.LOG_LEVEL));
        if (TextUtils.isEmpty(level)) {
            level = "INFO";
        }
        String message = VariableRuntimeUtils.applyTemplate(raw, ctx == null ? null : ctx.variables);
        if (TextUtils.isEmpty(message)) {
            message = "";
        }
        if (ctx != null && ctx.runtimeLogSink != null) {
            ctx.runtimeLogSink.log("[" + level.toUpperCase() + "] " + message);
        }
        if (ctx != null) {
            Map<String, Object> res = new HashMap<>();
            res.put(MetaOperation.MATCHED, true);
            res.put("message", message);
            ctx.currentResponse = res;
            ctx.lastOperation = obj;
        }
        return true;
    }

    private static String toString(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}

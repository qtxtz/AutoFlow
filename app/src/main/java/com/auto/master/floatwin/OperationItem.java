package com.auto.master.floatwin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OperationItem {
    public String name;
    public String type;
    public String id;
    public int index;
    public long delayDurationMs;
    public boolean delayShowCountdown;
    public long nodePreDelayMs;
    public long nodePreDelayMinMs;
    public long nodePreDelayMaxMs;
    public boolean nodePreDelayRandom;
    public List<String> templatePreviewNames = Collections.emptyList();

    public OperationItem(String name, String id, String type, int index) {
        this(name, id, type, index, 0L, true, 0L, false);
    }

    public OperationItem(String name, String id, String type, int index, long delayDurationMs) {
        this(name, id, type, index, delayDurationMs, true, 0L, false);
    }

    public OperationItem(String name, String id, String type, int index, long delayDurationMs, boolean delayShowCountdown) {
        this(name, id, type, index, delayDurationMs, delayShowCountdown, 0L, false);
    }

    public OperationItem(String name,
                         String id,
                         String type,
                         int index,
                         long delayDurationMs,
                         boolean delayShowCountdown,
                         long nodePreDelayMs,
                         boolean nodePreDelayRandom) {
        this(name, id, type, index, delayDurationMs, delayShowCountdown,
                nodePreDelayRandom ? 0L : nodePreDelayMs,
                nodePreDelayRandom ? nodePreDelayMs : nodePreDelayMs,
                nodePreDelayRandom ? nodePreDelayMs : nodePreDelayMs,
                nodePreDelayRandom);
    }

    public OperationItem(String name,
                         String id,
                         String type,
                         int index,
                         long delayDurationMs,
                         boolean delayShowCountdown,
                         long nodePreDelayMs,
                         long nodePreDelayMinMs,
                         long nodePreDelayMaxMs,
                         boolean nodePreDelayRandom) {
        this(name, id, type, index, delayDurationMs, delayShowCountdown,
                nodePreDelayMs, nodePreDelayMinMs, nodePreDelayMaxMs,
                nodePreDelayRandom, Collections.emptyList());
    }

    public OperationItem(String name,
                         String id,
                         String type,
                         int index,
                         long delayDurationMs,
                         boolean delayShowCountdown,
                         long nodePreDelayMs,
                         long nodePreDelayMinMs,
                         long nodePreDelayMaxMs,
                         boolean nodePreDelayRandom,
                         List<String> templatePreviewNames) {
        this.name = name;
        this.id = id;
        this.type = type;
        this.index = index;
        this.delayDurationMs = Math.max(0L, delayDurationMs);
        this.delayShowCountdown = delayShowCountdown;
        this.nodePreDelayMs = Math.max(0L, nodePreDelayMs);
        this.nodePreDelayMinMs = Math.max(0L, nodePreDelayMinMs);
        this.nodePreDelayMaxMs = Math.max(0L, nodePreDelayMaxMs);
        this.nodePreDelayRandom = nodePreDelayRandom;
        this.templatePreviewNames = templatePreviewNames == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(templatePreviewNames));
    }
}

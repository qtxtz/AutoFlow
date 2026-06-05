package com.auto.master.Task.Operation;

/**
 * TFLite/YOLO target detection node.
 */
public class AiDetectOperation extends MetaOperation {

    public AiDetectOperation() {
        this.setType(OperationType.AI_DETECT.getCode());
    }
}

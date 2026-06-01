package com.auto.master.Task.Operation;

/**
 * Wrapper operation: executes another operation with retry, then routes by its
 * own next/fallback settings.
 */
public class MtryOperation extends MetaOperation {

    public MtryOperation() {
        this.setType(25);
    }
}

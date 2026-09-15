package com.babylonjs.embedding;

/**
 * Describes a failed asynchronous JavaScript bridge operation.
 *
 * <p>The operation and property key are stable context. JavaScript name, message, and stack are
 * best-effort because engines and arbitrary thrown values provide different diagnostic fields.
 */
public final class JsException extends RuntimeException {
    private final String operation;
    private final String propertyKey;
    private final String jsName;
    private final String jsStack;

    JsException(
            String operation,
            String propertyKey,
            String jsName,
            String message,
            String jsStack) {
        super(message == null || message.isEmpty() ? "JavaScript operation failed" : message);
        this.operation = operation;
        this.propertyKey = propertyKey;
        this.jsName = jsName;
        this.jsStack = jsStack;
    }

    public String getOperation() {
        return operation;
    }

    public String getPropertyKey() {
        return propertyKey;
    }

    public String getJsName() {
        return jsName;
    }

    public String getJsStack() {
        return jsStack;
    }
}

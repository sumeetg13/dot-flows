package io.dotflows;

/** Base exception for everything the engine reports to callers. */
public class FlowException extends RuntimeException {

    public FlowException(String message) {
        super(message);
    }

    public FlowException(String message, Throwable cause) {
        super(message, cause);
    }
}

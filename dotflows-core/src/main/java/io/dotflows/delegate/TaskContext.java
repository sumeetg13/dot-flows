package io.dotflows.delegate;

import java.util.Map;

/** What a {@link TaskDelegate} sees of the running process instance. */
public interface TaskContext {

    String instanceId();

    String processKey();

    int processVersion();

    /** The caller-supplied reference given when the instance was started; may be null. */
    String businessKey();

    String nodeId();

    /** 1 for the first attempt, 2 for the first retry, and so on. */
    int attempt();

    /** Stable across retries of this task in this instance; pass it to providers that deduplicate. */
    String idempotencyKey();

    /** The task's inputs as configured in the modeler, already evaluated. */
    Map<String, Object> inputs();

    Object input(String name);

    /** @throws IllegalArgumentException when the input is present but not of the requested type */
    <T> T input(String name, Class<T> type);

    /** Read-only view of the instance's variables as they were when the task started. */
    Map<String, Object> variables();

    Object variable(String name);

    /** Stores a variable on the instance once the task completes. The value must be JSON-serializable. */
    void setVariable(String name, Object value);
}

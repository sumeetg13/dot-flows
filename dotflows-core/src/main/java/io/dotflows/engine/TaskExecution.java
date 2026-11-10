package io.dotflows.engine;

import io.dotflows.model.Node;

import java.util.Map;

/**
 * Everything a {@link TaskHandler} needs to run one attempt of a task.
 *
 * @param variables read-only snapshot of the instance's variables
 */
public record TaskExecution(String instanceId, String processKey, int processVersion, String businessKey, Node node,
                            int attempt, Map<String, Object> variables) {

    /** Stable across retries of this task in this instance. */
    public String idempotencyKey() {
        return instanceId + ":" + node.id();
    }
}

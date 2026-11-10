package io.dotflows.engine;

import io.dotflows.model.Node;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Executes one kind of task. The engine ships {@code delegate}, {@code http} and {@code decision};
 * register another implementation as a Spring bean to add a task type.
 */
public interface TaskHandler {

    /** The value of {@code taskType} in process JSON that selects this handler. */
    String taskType();

    /** Checks a node's configuration at deploy time, reporting each problem as a message. */
    default void validate(Node node, Consumer<String> problems) {
    }

    /**
     * Runs the task. Called outside any database transaction, possibly more than once for the same task.
     *
     * @return variables to set on the instance; may be empty
     * @throws Exception to fail this attempt and trigger the retry policy
     */
    Map<String, Object> execute(TaskExecution execution) throws Exception;
}

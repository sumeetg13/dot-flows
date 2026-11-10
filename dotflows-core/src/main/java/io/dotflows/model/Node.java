package io.dotflows.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A step in a process.
 *
 * @param taskType only for {@link NodeType#TASK}: which {@code TaskHandler} runs it (delegate, http, decision…)
 * @param config   task-type specific settings
 * @param retry    optional override of the engine's default retry policy
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Node(String id, String name, NodeType type, String taskType, Map<String, Object> config,
                   RetryPolicy retry) {

    public Node {
        config = config == null ? Map.of() : config;
    }

    public String configString(String key) {
        Object value = config.get(key);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> configMap(String key) {
        Object value = config.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}

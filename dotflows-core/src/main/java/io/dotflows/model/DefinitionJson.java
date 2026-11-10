package io.dotflows.model;

import io.dotflows.FlowException;
import io.dotflows.decision.DecisionTable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads and writes definitions and variable maps as JSON. */
public final class DefinitionJson {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public ProcessDefinition parseProcess(String json) {
        return read(json, ProcessDefinition.class);
    }

    public DecisionTable parseDecision(String json) {
        return read(json, DecisionTable.class);
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, MAP);
        } catch (JacksonException e) {
            throw new FlowException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new FlowException("Value cannot be written as JSON: " + e.getOriginalMessage(), e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            T value = mapper.readValue(json, type);
            if (value == null) {
                throw new FlowException("Definition is empty");
            }
            return value;
        } catch (JacksonException e) {
            throw new FlowException("Invalid definition JSON: " + e.getOriginalMessage(), e);
        }
    }
}

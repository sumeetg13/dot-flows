package io.dotflows.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A connecting line between two nodes.
 *
 * @param condition SpEL expression; only meaningful on edges leaving an exclusive gateway
 * @param isDefault the edge a gateway takes when no condition matches
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Edge(String id, String from, String to, String condition,
                   @JsonProperty("default") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean isDefault) {

    public boolean hasCondition() {
        return condition != null && !condition.isBlank();
    }
}

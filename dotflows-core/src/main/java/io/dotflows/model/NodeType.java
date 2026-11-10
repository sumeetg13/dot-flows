package io.dotflows.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum NodeType {
    @JsonProperty("start") START,
    @JsonProperty("end") END,
    @JsonProperty("task") TASK,
    @JsonProperty("exclusiveGateway") EXCLUSIVE_GATEWAY
}

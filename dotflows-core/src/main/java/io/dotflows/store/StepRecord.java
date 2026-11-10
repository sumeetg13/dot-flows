package io.dotflows.store;

import java.time.Instant;
import java.util.Map;

/**
 * One execution attempt of one node; together the steps of an instance are its history.
 *
 * @param id     assigned by the store; 0 before insertion
 * @param output variables the node set, or for a gateway the edge it took
 */
public record StepRecord(long id, String instanceId, String nodeId, String nodeType, Status status, int attempt,
                         Instant startedAt, Instant endedAt, String error, Map<String, Object> output) {

    public enum Status {COMPLETED, FAILED}
}

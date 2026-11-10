package io.dotflows.store;

import java.time.Instant;
import java.util.Map;

/** One run of a process, pinned to the definition version it started on. */
public record InstanceRecord(String id, long definitionId, String processKey, int version, String businessKey,
                             Status status, Map<String, Object> variables, String currentNodeId, String error,
                             Instant startedAt, Instant endedAt) {

    public enum Status {RUNNING, COMPLETED, FAILED, CANCELLED}

    public InstanceRecord at(String nodeId) {
        return new InstanceRecord(id, definitionId, processKey, version, businessKey, status, variables, nodeId,
                error, startedAt, endedAt);
    }

    public InstanceRecord withVariables(Map<String, Object> newVariables) {
        return new InstanceRecord(id, definitionId, processKey, version, businessKey, status, newVariables,
                currentNodeId, error, startedAt, endedAt);
    }

    public InstanceRecord running() {
        return new InstanceRecord(id, definitionId, processKey, version, businessKey, Status.RUNNING, variables,
                currentNodeId, null, startedAt, null);
    }

    public InstanceRecord ended(Status endStatus, String reason, Instant when) {
        return new InstanceRecord(id, definitionId, processKey, version, businessKey, endStatus, variables,
                currentNodeId, reason, startedAt, when);
    }
}

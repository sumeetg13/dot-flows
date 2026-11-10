package io.dotflows.store;

import java.time.Instant;

/**
 * A node waiting to be executed for an instance.
 *
 * @param attempt   which attempt running this job will be (1-based)
 * @param lockToken identifies the claim that currently owns the job; null when unclaimed
 */
public record JobRecord(long id, String instanceId, String nodeId, Instant dueAt, int attempt, String lockToken) {
}

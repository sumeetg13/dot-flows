package io.dotflows.store;

import java.time.Instant;
import java.util.List;

/** The job queue. All methods run inside the caller's transaction. */
public interface JobStore {

    void enqueue(String instanceId, String nodeId, Instant dueAt, int attempt);

    /**
     * Claims up to {@code max} jobs that are due and either unclaimed or whose claim has expired.
     * Must be safe when several engine nodes call it at once: a job goes to exactly one caller.
     */
    List<JobRecord> claim(String lockToken, Instant now, Instant lockedUntil, int max);

    /** Removes a finished job. @return false when the claim was lost to another worker in the meantime */
    boolean complete(long jobId, String lockToken);

    /** Releases the job for another attempt. @return false when the claim was lost in the meantime */
    boolean reschedule(long jobId, String lockToken, Instant dueAt, int attempt);

    void deleteForInstance(String instanceId);
}

package io.dotflows.spring.jdbc;

import io.dotflows.store.JobRecord;
import io.dotflows.store.JobStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;

import static io.dotflows.spring.jdbc.Times.instant;
import static io.dotflows.spring.jdbc.Times.param;

public class JdbcJobStore implements JobStore {

    private final JdbcClient jdbc;

    public JdbcJobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(String instanceId, String nodeId, Instant dueAt, int attempt) {
        jdbc.sql("INSERT INTO dflow_job (instance_id, node_id, due_at, attempt) VALUES (?, ?, ?, ?)")
                .params(instanceId, nodeId, param(dueAt), attempt).update();
    }

    @Override
    public List<JobRecord> claim(String lockToken, Instant now, Instant lockedUntil, int max) {
        // SKIP LOCKED lets concurrent workers pass over rows another worker is claiming instead of waiting.
        List<JobRecord> due = jdbc.sql("SELECT id, instance_id, node_id, due_at, attempt FROM dflow_job "
                        + "WHERE due_at <= ? AND (locked_until IS NULL OR locked_until < ?) "
                        + "ORDER BY due_at, id LIMIT ? FOR UPDATE SKIP LOCKED")
                .params(param(now), param(now), max)
                .query((rs, n) -> new JobRecord(rs.getLong("id"), rs.getString("instance_id"),
                        rs.getString("node_id"), instant(rs, "due_at"), rs.getInt("attempt"), lockToken))
                .list();
        for (JobRecord job : due) {
            jdbc.sql("UPDATE dflow_job SET lock_token = ?, locked_until = ? WHERE id = ?")
                    .params(lockToken, param(lockedUntil), job.id()).update();
        }
        return due;
    }

    @Override
    public boolean complete(long jobId, String lockToken) {
        return jdbc.sql("DELETE FROM dflow_job WHERE id = ? AND lock_token = ?").params(jobId, lockToken).update() == 1;
    }

    @Override
    public boolean reschedule(long jobId, String lockToken, Instant dueAt, int attempt) {
        return jdbc.sql("UPDATE dflow_job SET due_at = ?, attempt = ?, lock_token = NULL, locked_until = NULL "
                        + "WHERE id = ? AND lock_token = ?")
                .params(param(dueAt), attempt, jobId, lockToken).update() == 1;
    }

    @Override
    public void deleteForInstance(String instanceId) {
        jdbc.sql("DELETE FROM dflow_job WHERE instance_id = ?").param(instanceId).update();
    }
}

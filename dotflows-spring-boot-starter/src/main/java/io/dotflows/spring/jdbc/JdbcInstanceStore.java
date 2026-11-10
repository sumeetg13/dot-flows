package io.dotflows.spring.jdbc;

import io.dotflows.model.DefinitionJson;
import io.dotflows.store.InstanceQuery;
import io.dotflows.store.InstanceRecord;
import io.dotflows.store.InstanceRecord.Status;
import io.dotflows.store.InstanceStore;
import io.dotflows.store.StepRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.dotflows.spring.jdbc.Times.instant;
import static io.dotflows.spring.jdbc.Times.param;

public class JdbcInstanceStore implements InstanceStore {

    private static final String COLUMNS = "id, definition_id, process_key, version, business_key, status, variables, "
            + "current_node_id, error, started_at, ended_at";

    private final JdbcClient jdbc;
    private final DefinitionJson json;
    private final RowMapper<InstanceRecord> instanceMapper;
    private final RowMapper<StepRecord> stepMapper;

    public JdbcInstanceStore(JdbcClient jdbc, DefinitionJson json) {
        this.jdbc = jdbc;
        this.json = json;
        this.instanceMapper = (rs, n) -> new InstanceRecord(rs.getString("id"), rs.getLong("definition_id"),
                rs.getString("process_key"), rs.getInt("version"), rs.getString("business_key"),
                Status.valueOf(rs.getString("status")), json.readMap(rs.getString("variables")),
                rs.getString("current_node_id"), rs.getString("error"), instant(rs, "started_at"),
                instant(rs, "ended_at"));
        this.stepMapper = (rs, n) -> {
            String output = rs.getString("output");
            return new StepRecord(rs.getLong("id"), rs.getString("instance_id"), rs.getString("node_id"),
                    rs.getString("node_type"), StepRecord.Status.valueOf(rs.getString("status")), rs.getInt("attempt"),
                    instant(rs, "started_at"), instant(rs, "ended_at"), rs.getString("error"),
                    output == null ? null : json.readMap(output));
        };
    }

    @Override
    public void insert(InstanceRecord i) {
        jdbc.sql("INSERT INTO dflow_instance (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(i.id(), i.definitionId(), i.processKey(), i.version(), i.businessKey(), i.status().name(),
                        json.write(i.variables()), i.currentNodeId(), i.error(), param(i.startedAt()),
                        param(i.endedAt()))
                .update();
    }

    @Override
    public void update(InstanceRecord i) {
        jdbc.sql("UPDATE dflow_instance SET status = ?, variables = ?, current_node_id = ?, error = ?, ended_at = ? "
                        + "WHERE id = ?")
                .params(i.status().name(), json.write(i.variables()), i.currentNodeId(), i.error(),
                        param(i.endedAt()), i.id())
                .update();
    }

    @Override
    public Optional<InstanceRecord> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dflow_instance WHERE id = ?").param(id)
                .query(instanceMapper).optional();
    }

    @Override
    public Optional<InstanceRecord> lockById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dflow_instance WHERE id = ? FOR UPDATE").param(id)
                .query(instanceMapper).optional();
    }

    @Override
    public List<InstanceRecord> find(InstanceQuery query) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM dflow_instance WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (query.processKey() != null) {
            sql.append(" AND process_key = ?");
            params.add(query.processKey());
        }
        if (query.status() != null) {
            sql.append(" AND status = ?");
            params.add(query.status().name());
        }
        if (query.businessKey() != null) {
            sql.append(" AND business_key = ?");
            params.add(query.businessKey());
        }
        sql.append(" ORDER BY started_at DESC, id LIMIT ? OFFSET ?");
        params.add(query.limit());
        params.add(query.offset());
        return jdbc.sql(sql.toString()).params(params).query(instanceMapper).list();
    }

    @Override
    public void addStep(StepRecord s) {
        jdbc.sql("INSERT INTO dflow_step (instance_id, node_id, node_type, status, attempt, started_at, ended_at, "
                        + "error, output) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(s.instanceId(), s.nodeId(), s.nodeType(), s.status().name(), s.attempt(),
                        param(s.startedAt()), param(s.endedAt()), s.error(),
                        s.output() == null ? null : json.write(s.output()))
                .update();
    }

    @Override
    public List<StepRecord> listSteps(String instanceId) {
        return jdbc.sql("SELECT id, instance_id, node_id, node_type, status, attempt, started_at, ended_at, error, "
                        + "output FROM dflow_step WHERE instance_id = ? ORDER BY id")
                .param(instanceId).query(stepMapper).list();
    }
}

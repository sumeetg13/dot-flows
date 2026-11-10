package io.dotflows.spring.jdbc;

import io.dotflows.store.DefinitionRecord;
import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.DefinitionRecord.Status;
import io.dotflows.store.DefinitionStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static io.dotflows.spring.jdbc.Times.instant;
import static io.dotflows.spring.jdbc.Times.param;

public class JdbcDefinitionStore implements DefinitionStore {

    private static final String COLUMNS = "id, kind, def_key, version, status, active, name, body, updated_at";

    private static final RowMapper<DefinitionRecord> MAPPER = (rs, n) -> new DefinitionRecord(
            rs.getLong("id"), Kind.valueOf(rs.getString("kind")), rs.getString("def_key"), rs.getInt("version"),
            Status.valueOf(rs.getString("status")), rs.getBoolean("active"), rs.getString("name"),
            rs.getString("body"), instant(rs, "updated_at"));

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcDefinitionStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<DefinitionRecord> findDraft(Kind kind, String key) {
        return findVersionRow(kind, key, 0);
    }

    @Override
    public DefinitionRecord saveDraft(Kind kind, String key, String name, String json) {
        int updated = jdbc.sql("UPDATE dflow_definition SET name = ?, body = ?, updated_at = ? "
                        + "WHERE kind = ? AND def_key = ? AND version = 0")
                .params(name, json, param(clock.instant()), kind.name(), key).update();
        if (updated == 0) {
            insert(kind, key, 0, Status.DRAFT, false, name, json);
        }
        return findDraft(kind, key).orElseThrow();
    }

    @Override
    public DefinitionRecord insertVersion(Kind kind, String key, String name, String json) {
        // Lock this key's rows so two concurrent deployments cannot compute the same next version.
        List<Integer> versions = jdbc.sql("SELECT version FROM dflow_definition WHERE kind = ? AND def_key = ? FOR UPDATE")
                .params(kind.name(), key).query(Integer.class).list();
        int next = versions.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        deactivate(kind, key);
        long id = insert(kind, key, next, Status.DEPLOYED, true, name, json);
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<DefinitionRecord> findActive(Kind kind, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dflow_definition WHERE kind = ? AND def_key = ? AND active = TRUE")
                .params(kind.name(), key).query(MAPPER).optional();
    }

    @Override
    public Optional<DefinitionRecord> findVersion(Kind kind, String key, int version) {
        return version < 1 ? Optional.empty() : findVersionRow(kind, key, version);
    }

    @Override
    public Optional<DefinitionRecord> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dflow_definition WHERE id = ?").param(id).query(MAPPER).optional();
    }

    @Override
    public List<DefinitionRecord> listVersions(Kind kind, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dflow_definition WHERE kind = ? AND def_key = ? AND version > 0 "
                        + "ORDER BY version DESC")
                .params(kind.name(), key).query(MAPPER).list();
    }

    @Override
    public List<DefinitionRecord> listCurrent(Kind kind) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dflow_definition WHERE kind = ? AND (version = 0 OR active = TRUE) "
                        + "ORDER BY def_key")
                .param(kind.name()).query(MAPPER).list();
    }

    @Override
    public boolean activate(Kind kind, String key, int version) {
        List<Integer> versions = jdbc.sql("SELECT version FROM dflow_definition WHERE kind = ? AND def_key = ? FOR UPDATE")
                .params(kind.name(), key).query(Integer.class).list();
        if (version < 1 || !versions.contains(version)) {
            return false;
        }
        deactivate(kind, key);
        jdbc.sql("UPDATE dflow_definition SET active = TRUE WHERE kind = ? AND def_key = ? AND version = ?")
                .params(kind.name(), key, version).update();
        return true;
    }

    @Override
    public void delete(Kind kind, String key) {
        jdbc.sql("DELETE FROM dflow_definition WHERE kind = ? AND def_key = ?").params(kind.name(), key).update();
    }

    private Optional<DefinitionRecord> findVersionRow(Kind kind, String key, int version) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dflow_definition WHERE kind = ? AND def_key = ? AND version = ?")
                .params(kind.name(), key, version).query(MAPPER).optional();
    }

    private void deactivate(Kind kind, String key) {
        jdbc.sql("UPDATE dflow_definition SET active = FALSE WHERE kind = ? AND def_key = ? AND active = TRUE")
                .params(kind.name(), key).update();
    }

    private long insert(Kind kind, String key, int version, Status status, boolean active, String name, String json) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dflow_definition (kind, def_key, version, status, active, name, body, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(kind.name(), key, version, status.name(), active, name, json, param(clock.instant()))
                .update(keys, "id");
        return keys.getKey().longValue();
    }
}

package io.dotflows.store.memory;

import io.dotflows.store.DefinitionRecord;
import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.DefinitionRecord.Status;
import io.dotflows.store.DefinitionStore;
import io.dotflows.store.InstanceQuery;
import io.dotflows.store.InstanceRecord;
import io.dotflows.store.InstanceStore;
import io.dotflows.store.JobRecord;
import io.dotflows.store.JobStore;
import io.dotflows.store.StepRecord;
import io.dotflows.store.TransactionRunner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * All stores kept in memory, for unit tests and trying the engine without a database.
 * A "transaction" is one global lock; nothing is rolled back on failure.
 */
public class InMemoryStores implements DefinitionStore, InstanceStore, JobStore, TransactionRunner {

    private record Job(JobRecord record, Instant lockedUntil) {
    }

    private final Map<Long, DefinitionRecord> definitions = new LinkedHashMap<>();
    private final Map<String, InstanceRecord> instances = new LinkedHashMap<>();
    private final List<StepRecord> steps = new ArrayList<>();
    private final Map<Long, Job> jobs = new LinkedHashMap<>();
    private long sequence;

    @Override
    public synchronized <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }

    // ---- definitions

    @Override
    public synchronized Optional<DefinitionRecord> findDraft(Kind kind, String key) {
        return of(kind, key).filter(d -> d.status() == Status.DRAFT).findFirst();
    }

    @Override
    public synchronized DefinitionRecord saveDraft(Kind kind, String key, String name, String json) {
        long id = findDraft(kind, key).map(DefinitionRecord::id).orElseGet(() -> ++sequence);
        DefinitionRecord draft = new DefinitionRecord(id, kind, key, 0, Status.DRAFT, false, name, json, Instant.now());
        definitions.put(id, draft);
        return draft;
    }

    @Override
    public synchronized DefinitionRecord insertVersion(Kind kind, String key, String name, String json) {
        int next = listVersions(kind, key).stream().mapToInt(DefinitionRecord::version).max().orElse(0) + 1;
        deactivate(kind, key);
        DefinitionRecord version = new DefinitionRecord(++sequence, kind, key, next, Status.DEPLOYED, true, name, json,
                Instant.now());
        definitions.put(version.id(), version);
        return version;
    }

    @Override
    public synchronized Optional<DefinitionRecord> findActive(Kind kind, String key) {
        return of(kind, key).filter(DefinitionRecord::active).findFirst();
    }

    @Override
    public synchronized Optional<DefinitionRecord> findVersion(Kind kind, String key, int version) {
        return of(kind, key).filter(d -> d.status() == Status.DEPLOYED && d.version() == version).findFirst();
    }

    @Override
    public synchronized Optional<DefinitionRecord> findById(long id) {
        return Optional.ofNullable(definitions.get(id));
    }

    @Override
    public synchronized List<DefinitionRecord> listVersions(Kind kind, String key) {
        return of(kind, key).filter(d -> d.status() == Status.DEPLOYED)
                .sorted(Comparator.comparingInt(DefinitionRecord::version).reversed()).toList();
    }

    @Override
    public synchronized List<DefinitionRecord> listCurrent(Kind kind) {
        return definitions.values().stream()
                .filter(d -> d.kind() == kind && (d.status() == Status.DRAFT || d.active())).toList();
    }

    @Override
    public synchronized boolean activate(Kind kind, String key, int version) {
        Optional<DefinitionRecord> target = findVersion(kind, key, version);
        if (target.isEmpty()) {
            return false;
        }
        deactivate(kind, key);
        definitions.put(target.get().id(), withActive(target.get(), true));
        return true;
    }

    @Override
    public synchronized void delete(Kind kind, String key) {
        definitions.values().removeIf(d -> d.kind() == kind && d.key().equals(key));
    }

    private void deactivate(Kind kind, String key) {
        of(kind, key).filter(DefinitionRecord::active).toList()
                .forEach(d -> definitions.put(d.id(), withActive(d, false)));
    }

    private static DefinitionRecord withActive(DefinitionRecord d, boolean active) {
        return new DefinitionRecord(d.id(), d.kind(), d.key(), d.version(), d.status(), active, d.name(), d.json(),
                d.updatedAt());
    }

    private java.util.stream.Stream<DefinitionRecord> of(Kind kind, String key) {
        return List.copyOf(definitions.values()).stream().filter(d -> d.kind() == kind && d.key().equals(key));
    }

    // ---- instances

    @Override
    public synchronized void insert(InstanceRecord instance) {
        instances.put(instance.id(), instance);
    }

    @Override
    public synchronized void update(InstanceRecord instance) {
        instances.put(instance.id(), instance);
    }

    @Override
    public synchronized Optional<InstanceRecord> findById(String id) {
        return Optional.ofNullable(instances.get(id));
    }

    @Override
    public synchronized Optional<InstanceRecord> lockById(String id) {
        return findById(id);
    }

    @Override
    public synchronized List<InstanceRecord> find(InstanceQuery query) {
        return instances.values().stream()
                .filter(i -> query.processKey() == null || query.processKey().equals(i.processKey()))
                .filter(i -> query.status() == null || query.status() == i.status())
                .filter(i -> query.businessKey() == null || query.businessKey().equals(i.businessKey()))
                .sorted(Comparator.comparing(InstanceRecord::startedAt).reversed())
                .skip(query.offset()).limit(query.limit()).toList();
    }

    @Override
    public synchronized void addStep(StepRecord step) {
        steps.add(new StepRecord(++sequence, step.instanceId(), step.nodeId(), step.nodeType(), step.status(),
                step.attempt(), step.startedAt(), step.endedAt(), step.error(), step.output()));
    }

    @Override
    public synchronized List<StepRecord> listSteps(String instanceId) {
        return steps.stream().filter(s -> s.instanceId().equals(instanceId)).toList();
    }

    // ---- jobs

    @Override
    public synchronized void enqueue(String instanceId, String nodeId, Instant dueAt, int attempt) {
        long id = ++sequence;
        jobs.put(id, new Job(new JobRecord(id, instanceId, nodeId, dueAt, attempt, null), null));
    }

    @Override
    public synchronized List<JobRecord> claim(String lockToken, Instant now, Instant lockedUntil, int max) {
        List<JobRecord> claimed = new ArrayList<>();
        for (Job job : List.copyOf(jobs.values())) {
            if (claimed.size() >= max) {
                break;
            }
            boolean free = job.lockedUntil() == null || !job.lockedUntil().isAfter(now);
            if (free && !job.record().dueAt().isAfter(now)) {
                JobRecord r = job.record();
                JobRecord mine = new JobRecord(r.id(), r.instanceId(), r.nodeId(), r.dueAt(), r.attempt(), lockToken);
                jobs.put(r.id(), new Job(mine, lockedUntil));
                claimed.add(mine);
            }
        }
        return claimed;
    }

    @Override
    public synchronized boolean complete(long jobId, String lockToken) {
        if (!owns(jobId, lockToken)) {
            return false;
        }
        jobs.remove(jobId);
        return true;
    }

    @Override
    public synchronized boolean reschedule(long jobId, String lockToken, Instant dueAt, int attempt) {
        if (!owns(jobId, lockToken)) {
            return false;
        }
        JobRecord r = jobs.get(jobId).record();
        jobs.put(jobId, new Job(new JobRecord(r.id(), r.instanceId(), r.nodeId(), dueAt, attempt, null), null));
        return true;
    }

    @Override
    public synchronized void deleteForInstance(String instanceId) {
        jobs.values().removeIf(j -> j.record().instanceId().equals(instanceId));
    }

    private boolean owns(long jobId, String lockToken) {
        Job job = jobs.get(jobId);
        return job != null && lockToken != null && lockToken.equals(job.record().lockToken());
    }
}

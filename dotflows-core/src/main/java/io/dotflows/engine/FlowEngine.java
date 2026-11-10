package io.dotflows.engine;

import io.dotflows.FlowException;
import io.dotflows.NotFoundException;
import io.dotflows.expr.ExpressionEvaluator;
import io.dotflows.model.DefinitionJson;
import io.dotflows.model.Edge;
import io.dotflows.model.Node;
import io.dotflows.model.NodeType;
import io.dotflows.model.ProcessDefinition;
import io.dotflows.model.RetryPolicy;
import io.dotflows.store.DefinitionRecord;
import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.DefinitionStore;
import io.dotflows.store.InstanceQuery;
import io.dotflows.store.InstanceRecord;
import io.dotflows.store.InstanceRecord.Status;
import io.dotflows.store.InstanceStore;
import io.dotflows.store.JobRecord;
import io.dotflows.store.JobStore;
import io.dotflows.store.StepRecord;
import io.dotflows.store.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts process instances and moves them from node to node.
 *
 * <p>Every task runs as a job: the engine enqueues it, a worker claims and executes it, and on success the
 * instance moves on. Start, end and gateway nodes need no work and are passed through in the same transaction.
 */
public class FlowEngine {

    private static final Logger log = LoggerFactory.getLogger(FlowEngine.class);
    private static final int MAX_NODES_WITHOUT_TASK = 1000;
    private static final int MAX_ERROR_LENGTH = 2000;

    private final DefinitionStore definitions;
    private final InstanceStore instances;
    private final JobStore jobs;
    private final TransactionRunner transactions;
    private final Map<String, TaskHandler> handlers;
    private final ExpressionEvaluator expressions;
    private final DefinitionJson json;
    private final EngineOptions options;
    private final Clock clock;
    private final Map<Long, ProcessDefinition> parsed = new ConcurrentHashMap<>();

    public FlowEngine(DefinitionStore definitions, InstanceStore instances, JobStore jobs,
                      TransactionRunner transactions, Map<String, TaskHandler> handlers,
                      ExpressionEvaluator expressions, DefinitionJson json, EngineOptions options, Clock clock) {
        this.definitions = definitions;
        this.instances = instances;
        this.jobs = jobs;
        this.transactions = transactions;
        this.handlers = Map.copyOf(handlers);
        this.expressions = expressions;
        this.json = json;
        this.options = options;
        this.clock = clock;
    }

    // ---- starting and controlling instances

    /** Starts an instance on the active version. @return the instance id */
    public String start(String processKey, Map<String, Object> variables) {
        return start(processKey, variables, null);
    }

    /**
     * Starts an instance on the active version. Joins the caller's transaction when there is one, so the
     * instance only exists if the caller commits.
     *
     * @param businessKey optional reference of your own (an order number, say) to find the instance by
     * @return the instance id
     */
    public String start(String processKey, Map<String, Object> variables, String businessKey) {
        return transactions.inTransaction(() -> {
            DefinitionRecord definition = definitions.findActive(Kind.PROCESS, processKey)
                    .orElseThrow(() -> new NotFoundException("Process '" + processKey + "' has no deployed version"));
            return startOn(definition, variables, businessKey);
        });
    }

    /** Starts an instance on a specific deployed version instead of the active one. */
    public String startVersion(String processKey, int version, Map<String, Object> variables, String businessKey) {
        return transactions.inTransaction(() -> {
            DefinitionRecord definition = definitions.findVersion(Kind.PROCESS, processKey, version)
                    .orElseThrow(() -> new NotFoundException("Process '" + processKey + "' has no version " + version));
            return startOn(definition, variables, businessKey);
        });
    }

    /** Puts a failed instance back to work at the node where it failed, with a fresh set of attempts. */
    public void retry(String instanceId) {
        transactions.run(() -> {
            InstanceRecord instance = locked(instanceId);
            if (instance.status() != Status.FAILED) {
                throw new FlowException("Only failed instances can be retried; this one is " + instance.status());
            }
            jobs.deleteForInstance(instanceId);
            jobs.enqueue(instanceId, instance.currentNodeId(), clock.instant(), 1);
            instances.update(instance.running());
        });
    }

    /** Stops a running or failed instance for good. A task already executing is not interrupted. */
    public void cancel(String instanceId) {
        transactions.run(() -> {
            InstanceRecord instance = locked(instanceId);
            if (instance.status() != Status.RUNNING && instance.status() != Status.FAILED) {
                throw new FlowException("Instance is already " + instance.status());
            }
            jobs.deleteForInstance(instanceId);
            instances.update(instance.ended(Status.CANCELLED, instance.error(), clock.instant()));
        });
    }

    // ---- reading

    public InstanceRecord instance(String instanceId) {
        return transactions.inTransaction(() -> instances.findById(instanceId))
                .orElseThrow(() -> new NotFoundException("No instance " + instanceId));
    }

    public List<StepRecord> steps(String instanceId) {
        return transactions.inTransaction(() -> instances.listSteps(instanceId));
    }

    public List<InstanceRecord> find(InstanceQuery query) {
        return transactions.inTransaction(() -> instances.find(query));
    }

    // ---- job execution, called by JobWorker

    /** Claims due jobs for this caller; each claimed job must then be passed to {@link #execute(JobRecord)}. */
    public List<JobRecord> claim(int max) {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        return transactions.inTransaction(() -> jobs.claim(token, now, now.plus(options.lockDuration()), max));
    }

    /** Runs one claimed job to its conclusion: success, a scheduled retry, or a failed instance. */
    public void execute(JobRecord job) {
        Optional<InstanceRecord> found = transactions.inTransaction(() -> instances.findById(job.instanceId()));
        if (found.isEmpty() || found.get().status() != Status.RUNNING
                || !job.nodeId().equals(found.get().currentNodeId())) {
            transactions.run(() -> jobs.complete(job.id(), job.lockToken()));
            return;
        }
        InstanceRecord instance = found.get();
        ProcessDefinition process = process(instance.definitionId());
        Node node = process.node(job.nodeId());

        if (node.type() != NodeType.TASK) {
            // A retried gateway: nothing to run, just route again.
            transactions.run(() -> {
                InstanceRecord current = instances.lockById(instance.id()).orElse(null);
                if (jobs.complete(job.id(), job.lockToken()) && current != null && current.status() == Status.RUNNING) {
                    instances.update(enter(current, process, node));
                }
            });
            return;
        }

        Instant startedAt = clock.instant();
        Map<String, Object> updates = Map.of();
        Exception failure = null;
        try {
            TaskHandler handler = handlers.get(node.taskType());
            if (handler == null) {
                throw new FlowException("No handler for task type '" + node.taskType() + "'");
            }
            Map<String, Object> result = handler.execute(new TaskExecution(instance.id(), instance.processKey(),
                    instance.version(), instance.businessKey(), node, job.attempt(),
                    Collections.unmodifiableMap(instance.variables())));
            updates = result == null ? Map.of() : result;
        } catch (Exception e) {
            failure = e;
        }

        if (failure == null) {
            succeeded(job, process, node, startedAt, updates);
        } else {
            failed(job, node, startedAt, failure);
        }
    }

    /**
     * Claims and executes due jobs in the calling thread until none are left. Meant for tests and tools;
     * applications normally leave this to {@link JobWorker}.
     *
     * @return how many jobs were executed
     */
    public int drain() {
        int executed = 0;
        List<JobRecord> batch;
        while (!(batch = claim(10)).isEmpty()) {
            for (JobRecord job : batch) {
                execute(job);
                executed++;
            }
        }
        return executed;
    }

    // ---- internals

    private String startOn(DefinitionRecord definition, Map<String, Object> variables, String businessKey) {
        ProcessDefinition process = process(definition);
        Map<String, Object> initial = variables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(variables);
        // Fail now, in the caller's thread, if the variables cannot be stored.
        json.write(initial);
        Node start = process.startNode();
        InstanceRecord instance = new InstanceRecord(UUID.randomUUID().toString(), definition.id(),
                definition.key(), definition.version(), businessKey, Status.RUNNING, initial, start.id(), null,
                clock.instant(), null);
        instances.insert(instance);
        instances.update(enter(instance, process, start));
        return instance.id();
    }

    private void succeeded(JobRecord job, ProcessDefinition process, Node node, Instant startedAt,
                           Map<String, Object> updates) {
        transactions.run(() -> {
            InstanceRecord current = instances.lockById(job.instanceId()).orElse(null);
            if (!jobs.complete(job.id(), job.lockToken())) {
                log.warn("Job {} for instance {} was taken over by another worker; discarding this result. "
                        + "Consider raising the lock duration.", job.id(), job.instanceId());
                return;
            }
            if (current == null || current.status() != Status.RUNNING) {
                return;
            }
            Map<String, Object> variables = new LinkedHashMap<>(current.variables());
            variables.putAll(updates);
            try {
                json.write(variables);
            } catch (FlowException e) {
                step(current, node, StepRecord.Status.FAILED, job.attempt(), startedAt, e.getMessage(), null);
                instances.update(current.ended(Status.FAILED, e.getMessage(), clock.instant()));
                return;
            }
            step(current, node, StepRecord.Status.COMPLETED, job.attempt(), startedAt, null, updates);
            Node next = process.node(process.outgoing(node.id()).get(0).to());
            instances.update(enter(current.withVariables(variables), process, next));
        });
    }

    private void failed(JobRecord job, Node node, Instant startedAt, Exception failure) {
        String error = describe(failure);
        RetryPolicy retry = node.retry() != null ? node.retry() : options.defaultRetry();
        boolean retryable = job.attempt() < retry.maxAttempts();
        log.warn("Task '{}' of instance {} failed on attempt {}/{}: {}", node.id(), job.instanceId(),
                job.attempt(), retry.maxAttempts(), error, failure);

        transactions.run(() -> {
            InstanceRecord current = instances.lockById(job.instanceId()).orElse(null);
            Instant now = clock.instant();
            boolean stillOwned = retryable
                    ? jobs.reschedule(job.id(), job.lockToken(), now.plusSeconds(retry.backoffSeconds()), job.attempt() + 1)
                    : jobs.complete(job.id(), job.lockToken());
            if (!stillOwned || current == null || current.status() != Status.RUNNING) {
                return;
            }
            step(current, node, StepRecord.Status.FAILED, job.attempt(), startedAt, error, null);
            if (!retryable) {
                instances.update(current.ended(Status.FAILED, error, now));
            }
        });
    }

    /**
     * Arrives at a node and keeps walking until the instance has to wait for a task, ends, or fails.
     * The caller stores the returned record.
     */
    private InstanceRecord enter(InstanceRecord instance, ProcessDefinition process, Node node) {
        for (int walked = 0; walked < MAX_NODES_WITHOUT_TASK; walked++) {
            instance = instance.at(node.id());
            Instant now = clock.instant();
            switch (node.type()) {
                case TASK -> {
                    jobs.enqueue(instance.id(), node.id(), now, 1);
                    return instance;
                }
                case END -> {
                    step(instance, node, StepRecord.Status.COMPLETED, 1, now, null, null);
                    return instance.ended(Status.COMPLETED, null, now);
                }
                case START -> {
                    step(instance, node, StepRecord.Status.COMPLETED, 1, now, null, null);
                    node = process.node(process.outgoing(node.id()).get(0).to());
                }
                case EXCLUSIVE_GATEWAY -> {
                    Edge taken;
                    try {
                        taken = route(process, node, instance.variables());
                    } catch (FlowException e) {
                        step(instance, node, StepRecord.Status.FAILED, 1, now, e.getMessage(), null);
                        return instance.ended(Status.FAILED, e.getMessage(), now);
                    }
                    step(instance, node, StepRecord.Status.COMPLETED, 1, now, null, Map.of("takenTo", taken.to()));
                    node = process.node(taken.to());
                }
            }
        }
        String error = "Passed through " + MAX_NODES_WITHOUT_TASK + " nodes without reaching a task or an end; "
                + "the process probably loops between gateways";
        return instance.ended(Status.FAILED, error, clock.instant());
    }

    private Edge route(ProcessDefinition process, Node gateway, Map<String, Object> variables) {
        Edge fallback = null;
        for (Edge edge : process.outgoing(gateway.id())) {
            if (edge.isDefault()) {
                fallback = edge;
            } else if (edge.hasCondition() && expressions.evaluateBoolean(edge.condition(), variables)) {
                return edge;
            }
        }
        if (fallback == null) {
            throw new FlowException("No condition matched at gateway '" + gateway.id() + "' and it has no default");
        }
        return fallback;
    }

    private void step(InstanceRecord instance, Node node, StepRecord.Status status, int attempt, Instant startedAt,
                      String error, Map<String, Object> output) {
        instances.addStep(new StepRecord(0, instance.id(), node.id(), node.type().name(), status, attempt, startedAt,
                clock.instant(), error, output));
    }

    private InstanceRecord locked(String instanceId) {
        return instances.lockById(instanceId).orElseThrow(() -> new NotFoundException("No instance " + instanceId));
    }

    private ProcessDefinition process(long definitionId) {
        ProcessDefinition cached = parsed.get(definitionId);
        if (cached != null) {
            return cached;
        }
        DefinitionRecord record = transactions.inTransaction(() -> definitions.findById(definitionId))
                .orElseThrow(() -> new FlowException("Definition " + definitionId + " no longer exists"));
        return process(record);
    }

    private ProcessDefinition process(DefinitionRecord record) {
        return parsed.computeIfAbsent(record.id(), id -> json.parseProcess(record.json()));
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        String text = message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e instanceof FlowException ? message : e.getClass().getSimpleName() + ": " + message;
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}

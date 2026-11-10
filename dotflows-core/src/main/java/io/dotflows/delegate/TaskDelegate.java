package io.dotflows.delegate;

/**
 * Reusable business logic a delegate task calls: send an email, charge a card, write a record.
 *
 * <p>Register an implementation as a Spring bean annotated with {@link FlowDelegate}. Tasks are delivered
 * at least once, so make side effects idempotent; {@link TaskContext#idempotencyKey()} helps.
 */
@FunctionalInterface
public interface TaskDelegate {

    /** Throwing fails the attempt; the engine retries according to the task's retry policy. */
    void execute(TaskContext context) throws Exception;
}

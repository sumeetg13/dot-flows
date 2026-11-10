package io.dotflows.engine;

import io.dotflows.model.RetryPolicy;

import java.time.Duration;

/**
 * Engine-wide settings.
 *
 * @param defaultRetry used for tasks that do not set their own retry policy
 * @param lockDuration how long a worker may hold a job before another worker may take it over; must be
 *                     longer than the slowest task
 */
public record EngineOptions(RetryPolicy defaultRetry, Duration lockDuration) {

    public static EngineOptions defaults() {
        return new EngineOptions(new RetryPolicy(3, 10), Duration.ofMinutes(5));
    }
}

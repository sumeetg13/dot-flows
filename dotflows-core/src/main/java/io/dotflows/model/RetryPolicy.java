package io.dotflows.model;

/**
 * How often a failing task is attempted before the instance is marked failed.
 *
 * @param maxAttempts    total attempts including the first one (minimum 1)
 * @param backoffSeconds delay between attempts
 */
public record RetryPolicy(int maxAttempts, long backoffSeconds) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            maxAttempts = 1;
        }
        if (backoffSeconds < 0) {
            backoffSeconds = 0;
        }
    }
}

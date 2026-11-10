package io.dotflows.engine;

import io.dotflows.store.JobRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls the job queue and executes jobs on a thread pool. Several workers, in one JVM or many, can share
 * a database; each job goes to one of them.
 */
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final FlowEngine engine;
    private final int threads;
    private final Duration pollInterval;
    private final AtomicInteger busy = new AtomicInteger();

    private ScheduledExecutorService poller;
    private ExecutorService pool;

    public JobWorker(FlowEngine engine, int threads, Duration pollInterval) {
        this.engine = engine;
        this.threads = Math.max(1, threads);
        this.pollInterval = pollInterval;
    }

    public synchronized void start() {
        if (poller != null) {
            return;
        }
        pool = Executors.newFixedThreadPool(threads, named("dotflows-worker-"));
        poller = Executors.newSingleThreadScheduledExecutor(named("dotflows-poller-"));
        poller.scheduleWithFixedDelay(this::poll, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops polling and waits up to the given time for running tasks to finish. */
    public synchronized void stop(Duration grace) {
        if (poller == null) {
            return;
        }
        poller.shutdownNow();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Tasks still running after {}; their jobs will be retried once their locks expire", grace);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        poller = null;
        pool = null;
    }

    public synchronized boolean isRunning() {
        return poller != null;
    }

    private void poll() {
        try {
            int free = threads - busy.get();
            if (free <= 0) {
                return;
            }
            List<JobRecord> claimed = engine.claim(free);
            for (JobRecord job : claimed) {
                busy.incrementAndGet();
                pool.execute(() -> run(job));
            }
        } catch (Throwable t) {
            log.error("Polling for jobs failed; will try again in {}", pollInterval, t);
        }
    }

    private void run(JobRecord job) {
        try {
            engine.execute(job);
        } catch (Throwable t) {
            log.error("Job {} for instance {} could not be processed; it will be retried when its lock expires",
                    job.id(), job.instanceId(), t);
        } finally {
            busy.decrementAndGet();
            // The instance has probably just queued its next task: look for it without waiting a full interval.
            ScheduledExecutorService current = poller;
            if (current != null && !current.isShutdown()) {
                try {
                    current.execute(this::poll);
                } catch (RuntimeException ignored) {
                    // shutting down
                }
            }
        }
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

package io.dotflows.spring;

import io.dotflows.engine.DefinitionService;
import io.dotflows.engine.FlowEngine;
import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.InstanceRecord.Status;
import io.dotflows.store.JobRecord;
import io.dotflows.store.JobStore;
import io.dotflows.store.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The behaviours that depend on real PostgreSQL locking. Skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"dotflows.worker.enabled=false", "dotflows.retry.backoff=0s"})
class PostgresTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    FlowEngine engine;
    @Autowired
    DefinitionService definitions;
    @Autowired
    JobStore jobs;
    @Autowired
    TransactionRunner transactions;
    @Autowired
    TestApp.SendEmail sendEmail;

    @Test
    void runsAProcessEndToEnd() {
        definitions.deploy(Kind.PROCESS, Definitions.emailProcess("pg-welcome", "#email"));
        String id = engine.start("pg-welcome", Map.of("email", "pg@example.com"), "bk-1");

        assertThat(engine.drain()).isEqualTo(1);

        assertThat(engine.instance(id).status()).isEqualTo(Status.COMPLETED);
        assertThat(engine.instance(id).variables()).containsEntry("emailed", true);
        assertThat(sendEmail.sent).contains("pg@example.com");
        assertThat(engine.steps(id)).hasSize(3);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameJob() throws Exception {
        definitions.deploy(Kind.PROCESS, Definitions.emailProcess("pg-claim", "#email"));
        Set<String> started = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            started.add(engine.start("pg-claim", Map.of("email", "x@example.com")));
        }

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CyclicBarrier together = new CyclicBarrier(workers);
        List<Future<List<JobRecord>>> results = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            Callable<List<JobRecord>> claimAll = () -> {
                together.await();
                List<JobRecord> mine = new ArrayList<>();
                List<JobRecord> batch;
                while (!(batch = engine.claim(3)).isEmpty()) {
                    mine.addAll(batch);
                }
                return mine;
            };
            results.add(pool.submit(claimAll));
        }
        List<JobRecord> claimed = new ArrayList<>();
        for (Future<List<JobRecord>> result : results) {
            claimed.addAll(result.get());
        }
        pool.shutdown();

        List<JobRecord> ours = claimed.stream().filter(j -> started.contains(j.instanceId())).toList();
        assertThat(ours).hasSize(40);
        assertThat(ours).extracting(JobRecord::id).doesNotHaveDuplicates();

        ours.forEach(engine::execute);
        assertThat(started).allSatisfy(id -> assertThat(engine.instance(id).status()).isEqualTo(Status.COMPLETED));
    }

    @Test
    void expiredLockIsTakenOverAndTheStaleWorkerIsIgnored() {
        definitions.deploy(Kind.PROCESS, Definitions.emailProcess("pg-expiry", "#email"));
        String id = engine.start("pg-expiry", Map.of("email", "y@example.com"));
        Instant now = Instant.now();

        // A worker claims the job, then "crashes": its lock is already in the past.
        String staleToken = UUID.randomUUID().toString();
        List<JobRecord> stale = transactions.inTransaction(() ->
                jobs.claim(staleToken, now, now.minusSeconds(1), 100)).stream()
                .filter(j -> j.instanceId().equals(id)).toList();
        assertThat(stale).hasSize(1);

        // Another worker takes over and finishes the work.
        List<JobRecord> takeover = engine.claim(100).stream().filter(j -> j.instanceId().equals(id)).toList();
        assertThat(takeover).hasSize(1);
        engine.execute(takeover.get(0));
        assertThat(engine.instance(id).status()).isEqualTo(Status.COMPLETED);
        int stepsAfterTakeover = engine.steps(id).size();

        // The first worker wakes up late; its result must not be applied a second time.
        engine.execute(stale.get(0));
        assertThat(engine.steps(id)).hasSize(stepsAfterTakeover);
    }
}

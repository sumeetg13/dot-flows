package io.dotflows;

import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.InstanceRecord;
import io.dotflows.store.InstanceRecord.Status;
import io.dotflows.store.StepRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowEngineTest {

    private static final String NOTIFY = """
            {
              "processKey": "notify",
              "name": "Notify customer",
              "nodes": [
                {"id": "start", "type": "start"},
                {"id": "pick", "type": "task", "taskType": "decision",
                 "config": {"decisionKey": "channel", "resultVariable": "channel"}},
                {"id": "gw", "type": "exclusiveGateway"},
                {"id": "email", "type": "task", "taskType": "delegate",
                 "config": {"delegate": "sendEmail", "inputs": {"to": "#customer.email", "copies": 2}}},
                {"id": "sms", "type": "task", "taskType": "delegate",
                 "config": {"delegate": "sendSms", "inputs": {"to": "customer.phone"}}},
                {"id": "end", "type": "end"}
              ],
              "edges": [
                {"from": "start", "to": "pick"},
                {"from": "pick", "to": "gw"},
                {"from": "gw", "to": "email", "condition": "#channel == 'EMAIL'"},
                {"from": "gw", "to": "sms", "default": true},
                {"from": "email", "to": "end"},
                {"from": "sms", "to": "end"}
              ]
            }
            """;

    private static final String CHANNEL = """
            {
              "decisionKey": "channel",
              "hitPolicy": "FIRST",
              "inputs": [{"id": "total", "label": "Order total", "expression": "#order.total"}],
              "outputs": [{"name": "channel"}],
              "rules": [
                {"when": {"total": {"op": ">=", "value": 100}}, "then": {"channel": "EMAIL"}},
                {"then": {"channel": "SMS"}}
              ]
            }
            """;

    private final TestEngine t = new TestEngine();
    private final List<String> sent = new ArrayList<>();

    FlowEngineTest() {
        t.delegates.register("sendEmail", ctx -> {
            sent.add("email:" + ctx.input("to", String.class) + ":" + ctx.input("copies"));
            ctx.setVariable("messageId", "m-1");
        });
        t.delegates.register("sendSms", ctx -> sent.add("sms:" + ctx.input("to")));
    }

    private Map<String, Object> order(int total) {
        return Map.of("order", Map.of("total", total),
                "customer", Map.of("email", "a@example.com", "phone", "+100"));
    }

    private void deployNotify() {
        t.definitions.deploy(Kind.DECISION, CHANNEL);
        t.definitions.deploy(Kind.PROCESS, NOTIFY);
    }

    @Test
    void runsDecisionThenTakesMatchingBranch() {
        deployNotify();

        String id = t.engine.start("notify", order(250), "order-1");
        t.engine.drain();

        InstanceRecord instance = t.engine.instance(id);
        assertThat(instance.status()).isEqualTo(Status.COMPLETED);
        assertThat(instance.businessKey()).isEqualTo("order-1");
        assertThat(instance.variables()).containsEntry("channel", "EMAIL").containsEntry("messageId", "m-1");
        assertThat(sent).containsExactly("email:a@example.com:2");
        assertThat(t.engine.steps(id)).extracting(StepRecord::nodeId)
                .containsExactly("start", "pick", "gw", "email", "end");
    }

    @Test
    void takesDefaultBranchWhenNoConditionMatches() {
        deployNotify();

        String id = t.engine.start("notify", order(5));
        t.engine.drain();

        assertThat(t.engine.instance(id).status()).isEqualTo(Status.COMPLETED);
        assertThat(sent).containsExactly("sms:+100");
    }

    @Test
    void retriesThenFailsThenRecoversOnManualRetry() {
        AtomicInteger calls = new AtomicInteger();
        t.delegates.register("flaky", ctx -> {
            if (calls.incrementAndGet() <= 3) {
                throw new IllegalStateException("provider down");
            }
        });
        t.definitions.deploy(Kind.PROCESS, single("flaky-process", "flaky"));

        String id = t.engine.start("flaky-process", Map.of());
        t.engine.drain();

        InstanceRecord failed = t.engine.instance(id);
        assertThat(calls).hasValue(3);
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.currentNodeId()).isEqualTo("work");
        assertThat(failed.error()).contains("provider down");
        assertThat(t.engine.steps(id)).filteredOn(s -> s.status() == StepRecord.Status.FAILED)
                .extracting(StepRecord::attempt).containsExactly(1, 2, 3);

        t.engine.retry(id);
        t.engine.drain();

        assertThat(calls).hasValue(4);
        assertThat(t.engine.instance(id).status()).isEqualTo(Status.COMPLETED);
        assertThat(t.engine.instance(id).error()).isNull();
    }

    @Test
    void honoursPerTaskRetryPolicy() {
        AtomicInteger calls = new AtomicInteger();
        t.delegates.register("broken", ctx -> {
            calls.incrementAndGet();
            throw new IllegalStateException("no");
        });
        t.definitions.deploy(Kind.PROCESS, single("once", "broken")
                .replace("\"taskType\": \"delegate\"", "\"taskType\": \"delegate\", \"retry\": {\"maxAttempts\": 1}"));

        String id = t.engine.start("once", Map.of());
        t.engine.drain();

        assertThat(calls).hasValue(1);
        assertThat(t.engine.instance(id).status()).isEqualTo(Status.FAILED);
    }

    @Test
    void runningInstancesStayOnTheirVersionAndRollbackWorks() {
        List<String> ran = new ArrayList<>();
        t.delegates.register("one", ctx -> ran.add("one"));
        t.delegates.register("two", ctx -> ran.add("two"));

        assertThat(t.definitions.deploy(Kind.PROCESS, single("p", "one")).version()).isEqualTo(1);
        String first = t.engine.start("p", Map.of());

        assertThat(t.definitions.deploy(Kind.PROCESS, single("p", "two")).version()).isEqualTo(2);
        String second = t.engine.start("p", Map.of());
        t.engine.drain();

        assertThat(t.engine.instance(first).version()).isEqualTo(1);
        assertThat(t.engine.instance(second).version()).isEqualTo(2);
        assertThat(ran).containsExactly("one", "two");

        t.definitions.activate(Kind.PROCESS, "p", 1);
        String third = t.engine.start("p", Map.of());
        t.engine.drain();

        assertThat(t.engine.instance(third).version()).isEqualTo(1);
        assertThat(ran).containsExactly("one", "two", "one");
        assertThat(t.definitions.list(Kind.PROCESS)).singleElement()
                .satisfies(s -> {
                    assertThat(s.activeVersion()).isEqualTo(1);
                    assertThat(s.undeployedChanges()).isTrue();
                });
    }

    @Test
    void redeployingIdenticalContentDoesNotCreateAVersion() {
        t.delegates.register("one", ctx -> {
        });
        t.definitions.deploy(Kind.PROCESS, single("p", "one"));
        assertThat(t.definitions.deploy(Kind.PROCESS, single("p", "one")).version()).isEqualTo(1);
        assertThat(t.definitions.versions(Kind.PROCESS, "p")).hasSize(1);
    }

    @Test
    void cancelStopsFurtherWork() {
        AtomicInteger calls = new AtomicInteger();
        t.delegates.register("one", ctx -> calls.incrementAndGet());
        t.definitions.deploy(Kind.PROCESS, single("p", "one"));

        String id = t.engine.start("p", Map.of());
        t.engine.cancel(id);
        t.engine.drain();

        assertThat(calls).hasValue(0);
        assertThat(t.engine.instance(id).status()).isEqualTo(Status.CANCELLED);
        assertThatThrownBy(() -> t.engine.retry(id)).isInstanceOf(FlowException.class);
    }

    @Test
    void failsInstanceWhenGatewayConditionCannotBeEvaluated() {
        deployNotify();
        t.definitions.deploy(Kind.DECISION, CHANNEL.replace("\"channel\": \"EMAIL\"", "\"channel\": 7"));
        t.definitions.deploy(Kind.PROCESS, NOTIFY.replace("#channel == 'EMAIL'", "#channel"));

        String id = t.engine.start("notify", order(250));
        t.engine.drain();

        InstanceRecord instance = t.engine.instance(id);
        assertThat(instance.status()).isEqualTo(Status.FAILED);
        assertThat(instance.currentNodeId()).isEqualTo("gw");
        assertThat(instance.error()).contains("must return true or false");
    }

    @Test
    void startingAnUndeployedProcessIsAnError() {
        assertThatThrownBy(() -> t.engine.start("nope", Map.of())).isInstanceOf(NotFoundException.class);
    }

    static String single(String key, String delegate) {
        return """
                {
                  "processKey": "%s",
                  "nodes": [
                    {"id": "start", "type": "start"},
                    {"id": "work", "type": "task", "taskType": "delegate", "config": {"delegate": "%s"}},
                    {"id": "end", "type": "end"}
                  ],
                  "edges": [{"from": "start", "to": "work"}, {"from": "work", "to": "end"}]
                }
                """.formatted(key, delegate);
    }
}

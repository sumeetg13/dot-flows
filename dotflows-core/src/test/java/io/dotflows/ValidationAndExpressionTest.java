package io.dotflows;

import io.dotflows.decision.DecisionTable;
import io.dotflows.model.ValidationError;
import io.dotflows.store.DefinitionRecord.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationAndExpressionTest {

    private final TestEngine t = new TestEngine();

    private List<String> problems(String processJson) {
        return t.definitions.validate(Kind.PROCESS, processJson).stream().map(ValidationError::toString).toList();
    }

    @Test
    void acceptsAValidProcess() {
        t.delegates.register("one", ctx -> {
        });
        assertThat(problems(FlowEngineTest.single("p", "one"))).isEmpty();
    }

    @Test
    void reportsUnknownDelegate() {
        assertThat(problems(FlowEngineTest.single("p", "ghost")))
                .containsExactly("work: delegate 'ghost' is not registered in this application");
    }

    @Test
    void reportsStructuralProblems() {
        String json = """
                {
                  "processKey": "p",
                  "nodes": [
                    {"id": "start", "type": "start"},
                    {"id": "gw", "type": "exclusiveGateway"},
                    {"id": "a", "type": "task", "taskType": "teleport"},
                    {"id": "orphan", "type": "end"},
                    {"id": "end", "type": "end"}
                  ],
                  "edges": [
                    {"from": "start", "to": "gw"},
                    {"from": "gw", "to": "a", "condition": "#x >"},
                    {"from": "gw", "to": "end"},
                    {"from": "a", "to": "end"}
                  ]
                }
                """;
        List<String> problems = problems(json);
        assertThat(problems).anyMatch(p -> p.startsWith("gw: gateway needs exactly one default connection"));
        assertThat(problems).anyMatch(p -> p.startsWith("gw -> a: condition is not a valid expression"));
        assertThat(problems).contains("gw -> end: connection from a gateway needs a condition or must be the default");
        assertThat(problems).contains("a: unknown task type 'teleport'");
        assertThat(problems).contains("orphan: node cannot be reached from the start node");
    }

    @Test
    void refusesToDeployAnInvalidProcess() {
        assertThatThrownBy(() -> t.definitions.deploy(Kind.PROCESS, FlowEngineTest.single("p", "ghost")))
                .isInstanceOf(ValidationException.class);
        assertThat(t.definitions.versions(Kind.PROCESS, "p")).isEmpty();
    }

    @Test
    void draftCanBeInvalidButMustMatchItsKey() {
        t.definitions.saveDraft(Kind.PROCESS, "p", FlowEngineTest.single("p", "ghost"));
        assertThat(t.definitions.workingCopy(Kind.PROCESS, "p").version()).isZero();
        assertThatThrownBy(() -> t.definitions.saveDraft(Kind.PROCESS, "other", FlowEngineTest.single("p", "ghost")))
                .isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> t.definitions.deployDraft(Kind.PROCESS, "p")).isInstanceOf(ValidationException.class);
    }

    @Test
    void expressionsReadVariablesButCannotReachIntoTheJvm() {
        Map<String, Object> vars = Map.of("order", Map.of("total", 120, "items", List.of("a", "b")), "name", "Ada");

        assertThat(t.expressions.evaluate("#order.total > 100 and name == 'Ada'", vars)).isEqualTo(true);
        assertThat(t.expressions.evaluate("order.items[1]", vars)).isEqualTo("b");
        assertThat(t.expressions.evaluate("#missing", vars)).isNull();
        assertThat(t.expressions.evaluate("order.missing?.deeper", vars)).isNull();
        assertThat(t.expressions.render("Hi #{name}, total #{#order.total}", vars)).isEqualTo("Hi Ada, total 120");

        assertThatThrownBy(() -> t.expressions.evaluate("T(java.lang.Runtime).getRuntime()", vars))
                .isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> t.expressions.evaluate("new java.io.File('/tmp/x')", vars))
                .isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> t.expressions.evaluate("name.getClass()", vars))
                .isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> t.expressions.evaluate("@someBean", vars))
                .isInstanceOf(FlowException.class);
    }

    @Test
    void decisionOperatorsAndHitPolicies() {
        String json = """
                {
                  "decisionKey": "d",
                  "hitPolicy": "%s",
                  "inputs": [
                    {"id": "age", "expression": "#age"},
                    {"id": "country", "expression": "#country"}
                  ],
                  "outputs": [{"name": "tier"}, {"name": "discount"}],
                  "rules": [
                    {"when": {"age": {"op": "between", "value": [18, 25]}, "country": {"op": "in", "value": ["IN", "US"]}},
                     "then": {"tier": "young", "discount": 10}},
                    {"when": {"age": {"op": "expr", "value": "#input > 60 and #country != 'US'"}},
                     "then": {"tier": "senior", "discount": 20}},
                    {"when": {"country": {"op": "!=", "value": "FR"}}, "then": {"tier": "standard", "discount": 0}}
                  ]
                }
                """;
        DecisionTable first = t.json.parseDecision(json.formatted("FIRST"));
        DecisionTable collect = t.json.parseDecision(json.formatted("COLLECT"));
        DecisionTable unique = t.json.parseDecision(json.formatted("UNIQUE"));
        assertThat(t.decisions.validate(first)).isEmpty();

        assertThat(t.decisions.evaluate(first, Map.of("age", 20, "country", "IN")))
                .isEqualTo(Map.of("tier", "young", "discount", 10));
        assertThat(t.decisions.evaluate(first, Map.of("age", 70.5, "country", "IN")))
                .isEqualTo(Map.of("tier", "senior", "discount", 20));
        assertThat(t.decisions.evaluate(first, Map.of("age", 40, "country", "FR"))).isNull();
        assertThat(t.decisions.evaluate(collect, Map.of("age", 20, "country", "IN"))).asList().hasSize(2);
        assertThat(t.decisions.evaluate(collect, Map.of("age", 40, "country", "FR"))).asList().isEmpty();
        assertThatThrownBy(() -> t.decisions.evaluate(unique, Map.of("age", 20, "country", "IN")))
                .isInstanceOf(FlowException.class).hasMessageContaining("UNIQUE");
    }

    @Test
    void decisionValidationFindsBadCells() {
        DecisionTable table = t.json.parseDecision("""
                {
                  "decisionKey": "d",
                  "inputs": [{"id": "age", "expression": "#age"}],
                  "outputs": [{"name": "tier"}],
                  "rules": [
                    {"when": {"age": {"op": "~", "value": 1}, "height": {"op": "any"}}, "then": {"level": 1}},
                    {"when": {"age": {"op": "between", "value": [1]}}, "then": {"tier": "x"}}
                  ]
                }
                """);
        assertThat(t.decisions.validate(table)).extracting(ValidationError::toString).containsExactlyInAnyOrder(
                "rule 1: cell for 'age': unknown operator '~'",
                "rule 1: refers to unknown input column 'height'",
                "rule 1: sets unknown output 'level'",
                "rule 2: cell for 'age': 'between' needs a list of two values");
    }
}

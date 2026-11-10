package io.dotflows.engine;

import io.dotflows.FlowException;
import io.dotflows.decision.DecisionEvaluator;
import io.dotflows.decision.DecisionTable;
import io.dotflows.model.DefinitionJson;
import io.dotflows.model.Node;
import io.dotflows.store.DefinitionRecord;
import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.DefinitionStore;
import io.dotflows.store.TransactionRunner;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Task type {@code decision}: evaluates a deployed decision table and stores the result in a variable.
 *
 * <p>Config: {@code decisionKey}, {@code resultVariable}, and optionally {@code decisionVersion} to pin a
 * version instead of using whichever is active when the task runs.
 */
public class DecisionTaskHandler implements TaskHandler {

    public static final String TYPE = "decision";

    private final DefinitionStore definitions;
    private final TransactionRunner transactions;
    private final DecisionEvaluator evaluator;
    private final DefinitionJson json;

    public DecisionTaskHandler(DefinitionStore definitions, TransactionRunner transactions,
                               DecisionEvaluator evaluator, DefinitionJson json) {
        this.definitions = definitions;
        this.transactions = transactions;
        this.evaluator = evaluator;
        this.json = json;
    }

    @Override
    public String taskType() {
        return TYPE;
    }

    @Override
    public void validate(Node node, Consumer<String> problems) {
        String key = node.configString("decisionKey");
        if (key == null || key.isBlank()) {
            problems.accept("no decision selected");
        } else {
            try {
                if (find(node).isEmpty()) {
                    problems.accept(pinnedVersion(node) == null
                            ? "decision '" + key + "' has no deployed version"
                            : "decision '" + key + "' has no version " + pinnedVersion(node));
                }
            } catch (FlowException e) {
                problems.accept(e.getMessage());
            }
        }
        String resultVariable = node.configString("resultVariable");
        if (resultVariable == null || resultVariable.isBlank()) {
            problems.accept("resultVariable is required");
        }
    }

    @Override
    public Map<String, Object> execute(TaskExecution execution) {
        Node node = execution.node();
        DefinitionRecord record = find(node).orElseThrow(() ->
                new FlowException("Decision '" + node.configString("decisionKey") + "' is not deployed"));
        DecisionTable table = json.parseDecision(record.json());
        Map<String, Object> result = new HashMap<>();
        result.put(node.configString("resultVariable"), evaluator.evaluate(table, execution.variables()));
        return result;
    }

    private Optional<DefinitionRecord> find(Node node) {
        String key = node.configString("decisionKey");
        Integer version = pinnedVersion(node);
        return transactions.inTransaction(() -> version == null
                ? definitions.findActive(Kind.DECISION, key)
                : definitions.findVersion(Kind.DECISION, key, version));
    }

    private static Integer pinnedVersion(Node node) {
        Object value = node.config().get("decisionVersion");
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new FlowException("decisionVersion must be a number");
        }
    }
}

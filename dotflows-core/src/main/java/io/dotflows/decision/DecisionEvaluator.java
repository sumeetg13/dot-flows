package io.dotflows.decision;

import io.dotflows.FlowException;
import io.dotflows.decision.DecisionTable.Cell;
import io.dotflows.decision.DecisionTable.InputColumn;
import io.dotflows.decision.DecisionTable.OutputColumn;
import io.dotflows.decision.DecisionTable.Rule;
import io.dotflows.expr.ExpressionEvaluator;
import io.dotflows.model.ValidationError;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates and evaluates {@link DecisionTable}s. */
public class DecisionEvaluator {

    private static final Set<String> OPERATORS = Set.of("any", "==", "!=", "<", "<=", ">", ">=", "in", "between", "expr");

    private final ExpressionEvaluator expressions;

    public DecisionEvaluator(ExpressionEvaluator expressions) {
        this.expressions = expressions;
    }

    /**
     * Evaluates the table.
     *
     * <p>With a single output column the result is that column's value; with several it is a map of output name
     * to value. {@code COLLECT} wraps those in a list. When no rule matches the result is null (an empty list for
     * {@code COLLECT}).
     */
    public Object evaluate(DecisionTable table, Map<String, Object> variables) {
        Map<String, Object> inputValues = new HashMap<>();
        for (InputColumn input : table.inputs()) {
            inputValues.put(input.id(), expressions.evaluate(input.expression(), variables));
        }

        List<Object> matches = new ArrayList<>();
        for (Rule rule : table.rules()) {
            if (matches(table, rule, inputValues, variables)) {
                matches.add(result(table, rule));
                if (table.hitPolicy() == DecisionTable.HitPolicy.FIRST) {
                    break;
                }
            }
        }

        return switch (table.hitPolicy()) {
            case COLLECT -> matches;
            case FIRST -> matches.isEmpty() ? null : matches.get(0);
            case UNIQUE -> {
                if (matches.size() > 1) {
                    throw new FlowException("Decision '" + table.decisionKey() + "' has hit policy UNIQUE but "
                            + matches.size() + " rules matched");
                }
                yield matches.isEmpty() ? null : matches.get(0);
            }
        };
    }

    public List<ValidationError> validate(DecisionTable table) {
        List<ValidationError> errors = new ArrayList<>();
        if (isBlank(table.decisionKey())) {
            errors.add(new ValidationError(null, "decisionKey is required"));
        }
        Set<String> inputIds = new HashSet<>();
        for (InputColumn input : table.inputs()) {
            if (isBlank(input.id()) || !inputIds.add(input.id())) {
                errors.add(new ValidationError(input.id(), "input columns need a unique id"));
            }
            String problem = expressions.check(input.expression());
            if (problem != null) {
                errors.add(new ValidationError(input.id(), "input expression is invalid: " + problem));
            }
        }
        if (table.outputs().isEmpty()) {
            errors.add(new ValidationError(null, "at least one output column is required"));
        }
        Set<String> outputNames = new HashSet<>();
        for (OutputColumn output : table.outputs()) {
            if (isBlank(output.name()) || !outputNames.add(output.name())) {
                errors.add(new ValidationError(output.name(), "output columns need a unique name"));
            }
        }
        for (int i = 0; i < table.rules().size(); i++) {
            Rule rule = table.rules().get(i);
            String ruleId = rule.id() != null ? rule.id() : "rule " + (i + 1);
            rule.when().forEach((inputId, cell) -> {
                if (!inputIds.contains(inputId)) {
                    errors.add(new ValidationError(ruleId, "refers to unknown input column '" + inputId + "'"));
                }
                String problem = checkCell(cell);
                if (problem != null) {
                    errors.add(new ValidationError(ruleId, "cell for '" + inputId + "': " + problem));
                }
            });
            for (String outputName : rule.then().keySet()) {
                if (!outputNames.contains(outputName)) {
                    errors.add(new ValidationError(ruleId, "sets unknown output '" + outputName + "'"));
                }
            }
        }
        return errors;
    }

    private String checkCell(Cell cell) {
        if (cell == null || cell.op() == null) {
            return null;
        }
        if (!OPERATORS.contains(cell.op())) {
            return "unknown operator '" + cell.op() + "'";
        }
        return switch (cell.op()) {
            case "any" -> null;
            case "in" -> cell.value() instanceof List<?> ? null : "'in' needs a list of values";
            case "between" -> cell.value() instanceof List<?> list && list.size() == 2
                    ? null : "'between' needs a list of two values";
            case "expr" -> cell.value() instanceof String text
                    ? expressions.check(text) : "'expr' needs an expression";
            default -> cell.value() == null && !cell.op().equals("==") && !cell.op().equals("!=")
                    ? "'" + cell.op() + "' needs a value" : null;
        };
    }

    private boolean matches(DecisionTable table, Rule rule, Map<String, Object> inputValues, Map<String, Object> variables) {
        for (InputColumn input : table.inputs()) {
            Cell cell = rule.when().get(input.id());
            if (cell != null && !matches(cell, inputValues.get(input.id()), variables)) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(Cell cell, Object input, Map<String, Object> variables) {
        String op = cell.op() == null ? "any" : cell.op();
        Object value = cell.value();
        return switch (op) {
            case "any" -> true;
            case "==" -> equal(input, value);
            case "!=" -> !equal(input, value);
            case "<" -> comparable(input, value) && compare(input, value) < 0;
            case "<=" -> comparable(input, value) && compare(input, value) <= 0;
            case ">" -> comparable(input, value) && compare(input, value) > 0;
            case ">=" -> comparable(input, value) && compare(input, value) >= 0;
            case "in" -> value instanceof List<?> list && list.stream().anyMatch(v -> equal(input, v));
            case "between" -> value instanceof List<?> list && list.size() == 2
                    && comparable(input, list.get(0)) && comparable(input, list.get(1))
                    && compare(input, list.get(0)) >= 0 && compare(input, list.get(1)) <= 0;
            case "expr" -> {
                Map<String, Object> scope = new HashMap<>(variables);
                scope.put("input", input);
                yield expressions.evaluateBoolean(String.valueOf(value), scope);
            }
            default -> throw new FlowException("Unknown decision operator '" + op + "'");
        };
    }

    private Object result(DecisionTable table, Rule rule) {
        if (table.outputs().size() == 1) {
            return rule.then().get(table.outputs().get(0).name());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (OutputColumn output : table.outputs()) {
            result.put(output.name(), rule.then().get(output.name()));
        }
        return result;
    }

    private static boolean equal(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return compare(a, b) == 0;
        }
        return Objects.equals(a, b);
    }

    private static boolean comparable(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return true;
        }
        return a instanceof Comparable<?> && b != null && a.getClass() == b.getClass();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        }
        return ((Comparable) a).compareTo(b);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

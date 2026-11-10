package io.dotflows.decision;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A decision table: each rule tests the input columns and, when all its cells match, contributes its outputs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionTable(String decisionKey, String name, HitPolicy hitPolicy, List<InputColumn> inputs,
                            List<OutputColumn> outputs, List<Rule> rules) {

    public DecisionTable {
        hitPolicy = hitPolicy == null ? HitPolicy.FIRST : hitPolicy;
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public enum HitPolicy {
        /** The first matching rule wins. */
        FIRST,
        /** At most one rule may match; more than one is an error. */
        UNIQUE,
        /** Every matching rule contributes; the result is a list. */
        COLLECT
    }

    /** @param expression SpEL producing the value the column's cells are tested against */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InputColumn(String id, String label, String expression) {
    }

    /** @param name key of this output in the result */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputColumn(String name, String label) {
    }

    /**
     * @param when input column id to cell; a missing cell matches anything
     * @param then output name to literal value
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(String id, Map<String, Cell> when, Map<String, Object> then) {
        public Rule {
            when = when == null ? Map.of() : when;
            then = then == null ? Map.of() : then;
        }
    }

    /**
     * @param op    one of {@code any, ==, !=, <, <=, >, >=, in, between, expr}
     * @param value operand: a literal, a list for {@code in}/{@code between}, or SpEL text for {@code expr}
     *              (where {@code #input} is the column's value)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cell(String op, Object value) {
    }
}

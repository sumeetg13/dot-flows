package io.dotflows.expr;

import io.dotflows.FlowException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ParserContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates SpEL against process variables in a restricted context.
 *
 * <p>Variables are reachable as {@code #name} or bare {@code name}; nested maps and bean properties with dots.
 * Type references ({@code T(...)}), constructors, bean references and method calls are not available, because
 * whoever can edit a process must not be able to run arbitrary code in the host application.
 */
public class ExpressionEvaluator {

    private static final ParserContext TEMPLATE = ParserContext.TEMPLATE_EXPRESSION;

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> expressions = new ConcurrentHashMap<>();
    private final Map<String, Expression> templates = new ConcurrentHashMap<>();

    public Object evaluate(String expression, Map<String, Object> variables) {
        try {
            return parse(expression).getValue(context(variables));
        } catch (RuntimeException e) {
            throw new FlowException("Expression '" + expression + "' failed: " + e.getMessage(), e);
        }
    }

    public boolean evaluateBoolean(String expression, Map<String, Object> variables) {
        Object value = evaluate(expression, variables);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new FlowException("Expression '" + expression + "' must return true or false but returned " + value);
    }

    /** Renders text containing {@code #{...}} placeholders. */
    public String render(String template, Map<String, Object> variables) {
        if (template == null) {
            return null;
        }
        try {
            return parseTemplate(template).getValue(context(variables), String.class);
        } catch (RuntimeException e) {
            throw new FlowException("Template '" + template + "' failed: " + e.getMessage(), e);
        }
    }

    /** @return null when the expression parses, otherwise the reason it does not */
    public String check(String expression) {
        try {
            parse(expression);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /** @return null when the template parses, otherwise the reason it does not */
    public String checkTemplate(String template) {
        try {
            parseTemplate(template);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    private Expression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression is empty");
        }
        return expressions.computeIfAbsent(expression, parser::parseExpression);
    }

    private Expression parseTemplate(String template) {
        return templates.computeIfAbsent(template, t -> parser.parseExpression(t, TEMPLATE));
    }

    private EvaluationContext context(Map<String, Object> variables) {
        SimpleEvaluationContext context = SimpleEvaluationContext
                .forPropertyAccessors(new ReadOnlyMapAccessor(), DataBindingPropertyAccessor.forReadOnlyAccess())
                .withRootObject(variables)
                .build();
        variables.forEach(context::setVariable);
        return context;
    }

    /** Lets {@code order.total} read a map key; a missing key reads as null instead of failing. */
    private static final class ReadOnlyMapAccessor implements PropertyAccessor {

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[]{Map.class};
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof Map;
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) {
            return new TypedValue(((Map<?, ?>) target).get(name));
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue) {
            throw new UnsupportedOperationException("Variables are read-only inside expressions");
        }
    }
}

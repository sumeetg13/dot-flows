package io.dotflows.engine;

import io.dotflows.FlowException;
import io.dotflows.delegate.DelegateDescriptor;
import io.dotflows.delegate.DelegateRegistry;
import io.dotflows.delegate.TaskContext;
import io.dotflows.delegate.TaskDelegate;
import io.dotflows.expr.ExpressionEvaluator;
import io.dotflows.model.Node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Task type {@code delegate}: calls a registered {@link TaskDelegate}.
 *
 * <p>Config: {@code delegate} (name) and {@code inputs} (name to value). String input values are SpEL
 * expressions, so a literal string is written {@code 'like this'}; numbers and booleans pass through.
 */
public class DelegateTaskHandler implements TaskHandler {

    public static final String TYPE = "delegate";

    private final DelegateRegistry registry;
    private final ExpressionEvaluator expressions;

    public DelegateTaskHandler(DelegateRegistry registry, ExpressionEvaluator expressions) {
        this.registry = registry;
        this.expressions = expressions;
    }

    @Override
    public String taskType() {
        return TYPE;
    }

    @Override
    public void validate(Node node, Consumer<String> problems) {
        String name = node.configString("delegate");
        if (name == null || name.isBlank()) {
            problems.accept("no delegate selected");
            return;
        }
        if (registry.find(name).isEmpty()) {
            problems.accept("delegate '" + name + "' is not registered in this application");
            return;
        }
        Map<String, Object> inputs = node.configMap("inputs");
        inputs.forEach((input, value) -> {
            if (value instanceof String text && !text.isBlank()) {
                String problem = expressions.check(text);
                if (problem != null) {
                    problems.accept("input '" + input + "' is not a valid expression: " + problem);
                }
            }
        });
        registry.list().stream().filter(d -> d.name().equals(name)).findFirst()
                .ifPresent(descriptor -> {
                    for (DelegateDescriptor.Input declared : descriptor.inputs()) {
                        Object value = inputs.get(declared.name());
                        boolean missing = value == null || (value instanceof String s && s.isBlank());
                        if (declared.required() && missing) {
                            problems.accept("required input '" + declared.name() + "' is not set");
                        }
                    }
                });
    }

    @Override
    public Map<String, Object> execute(TaskExecution execution) throws Exception {
        String name = execution.node().configString("delegate");
        TaskDelegate delegate = registry.find(name)
                .orElseThrow(() -> new FlowException("Delegate '" + name + "' is not registered in this application"));

        Map<String, Object> inputs = new LinkedHashMap<>();
        execution.node().configMap("inputs").forEach((input, value) -> {
            if (value instanceof String text) {
                inputs.put(input, text.isBlank() ? null : expressions.evaluate(text, execution.variables()));
            } else {
                inputs.put(input, value);
            }
        });

        Context context = new Context(execution, inputs);
        delegate.execute(context);
        return context.updates;
    }

    private static final class Context implements TaskContext {

        private final TaskExecution execution;
        private final Map<String, Object> inputs;
        private final Map<String, Object> updates = new LinkedHashMap<>();

        private Context(TaskExecution execution, Map<String, Object> inputs) {
            this.execution = execution;
            this.inputs = Collections.unmodifiableMap(inputs);
        }

        @Override
        public String instanceId() {
            return execution.instanceId();
        }

        @Override
        public String processKey() {
            return execution.processKey();
        }

        @Override
        public int processVersion() {
            return execution.processVersion();
        }

        @Override
        public String businessKey() {
            return execution.businessKey();
        }

        @Override
        public String nodeId() {
            return execution.node().id();
        }

        @Override
        public int attempt() {
            return execution.attempt();
        }

        @Override
        public String idempotencyKey() {
            return execution.idempotencyKey();
        }

        @Override
        public Map<String, Object> inputs() {
            return inputs;
        }

        @Override
        public Object input(String name) {
            return inputs.get(name);
        }

        @Override
        public <T> T input(String name, Class<T> type) {
            Object value = inputs.get(name);
            if (value == null) {
                return null;
            }
            if (!type.isInstance(value)) {
                throw new IllegalArgumentException("Input '" + name + "' is a " + value.getClass().getSimpleName()
                        + ", not a " + type.getSimpleName());
            }
            return type.cast(value);
        }

        @Override
        public Map<String, Object> variables() {
            return execution.variables();
        }

        @Override
        public Object variable(String name) {
            return execution.variables().get(name);
        }

        @Override
        public void setVariable(String name, Object value) {
            updates.put(name, value);
        }
    }
}

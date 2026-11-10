package io.dotflows.engine;

import io.dotflows.expr.ExpressionEvaluator;
import io.dotflows.model.Edge;
import io.dotflows.model.Node;
import io.dotflows.model.NodeType;
import io.dotflows.model.ProcessDefinition;
import io.dotflows.model.ValidationError;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks that a process can be executed before it is deployed. */
public class ProcessValidator {

    public static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,99}");

    private final Map<String, TaskHandler> handlers;
    private final ExpressionEvaluator expressions;

    public ProcessValidator(Map<String, TaskHandler> handlers, ExpressionEvaluator expressions) {
        this.handlers = handlers;
        this.expressions = expressions;
    }

    public List<ValidationError> validate(ProcessDefinition process) {
        List<ValidationError> errors = new ArrayList<>();

        if (process.processKey() == null || !KEY.matcher(process.processKey()).matches()) {
            errors.add(new ValidationError(null,
                    "processKey must start with a letter and contain only letters, digits, '-' and '_'"));
        }

        Set<String> ids = new HashSet<>();
        for (Node node : process.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add(new ValidationError(null, "every node needs an id"));
            } else if (!ids.add(node.id())) {
                errors.add(new ValidationError(node.id(), "node id is used more than once"));
            }
            if (node.type() == null) {
                errors.add(new ValidationError(node.id(), "node has no type"));
            }
        }

        boolean edgesOk = true;
        for (Edge edge : process.edges()) {
            String label = edge.id() != null ? edge.id() : edge.from() + " -> " + edge.to();
            if (!ids.contains(edge.from()) || !ids.contains(edge.to())) {
                errors.add(new ValidationError(label, "connection refers to a node that does not exist"));
                edgesOk = false;
            }
        }

        long starts = process.nodes().stream().filter(n -> n.type() == NodeType.START).count();
        if (starts != 1) {
            errors.add(new ValidationError(null, "a process needs exactly one start node, found " + starts));
        }
        if (process.nodes().stream().noneMatch(n -> n.type() == NodeType.END)) {
            errors.add(new ValidationError(null, "a process needs at least one end node"));
        }
        if (!errors.isEmpty() || !edgesOk) {
            return errors;
        }

        for (Node node : process.nodes()) {
            List<Edge> out = process.outgoing(node.id());
            switch (node.type()) {
                case START -> {
                    if (!process.incoming(node.id()).isEmpty()) {
                        errors.add(new ValidationError(node.id(), "the start node cannot have incoming connections"));
                    }
                    requireSingleOutgoing(node, out, errors);
                }
                case END -> {
                    if (!out.isEmpty()) {
                        errors.add(new ValidationError(node.id(), "an end node cannot have outgoing connections"));
                    }
                }
                case TASK -> {
                    requireSingleOutgoing(node, out, errors);
                    validateTask(node, errors);
                }
                case EXCLUSIVE_GATEWAY -> validateGateway(node, out, errors);
            }
        }

        Set<String> reachable = reachableFrom(process, process.startNode().id());
        for (Node node : process.nodes()) {
            if (!reachable.contains(node.id())) {
                errors.add(new ValidationError(node.id(), "node cannot be reached from the start node"));
            }
        }
        return errors;
    }

    private void requireSingleOutgoing(Node node, List<Edge> out, List<ValidationError> errors) {
        if (out.size() != 1) {
            errors.add(new ValidationError(node.id(), "needs exactly one outgoing connection, found " + out.size()
                    + (out.size() > 1 ? " (use a gateway to branch)" : "")));
        }
    }

    private void validateTask(Node node, List<ValidationError> errors) {
        if (node.taskType() == null || node.taskType().isBlank()) {
            errors.add(new ValidationError(node.id(), "task has no taskType"));
            return;
        }
        TaskHandler handler = handlers.get(node.taskType());
        if (handler == null) {
            errors.add(new ValidationError(node.id(), "unknown task type '" + node.taskType() + "'"));
            return;
        }
        handler.validate(node, problem -> errors.add(new ValidationError(node.id(), problem)));
    }

    private void validateGateway(Node node, List<Edge> out, List<ValidationError> errors) {
        if (out.isEmpty()) {
            errors.add(new ValidationError(node.id(), "gateway has no outgoing connections"));
            return;
        }
        long defaults = out.stream().filter(Edge::isDefault).count();
        if (defaults != 1) {
            errors.add(new ValidationError(node.id(),
                    "gateway needs exactly one default connection, found " + defaults));
        }
        for (Edge edge : out) {
            if (edge.isDefault()) {
                continue;
            }
            String label = edge.id() != null ? edge.id() : edge.from() + " -> " + edge.to();
            if (!edge.hasCondition()) {
                errors.add(new ValidationError(label, "connection from a gateway needs a condition or must be the default"));
            } else {
                String problem = expressions.check(edge.condition());
                if (problem != null) {
                    errors.add(new ValidationError(label, "condition is not a valid expression: " + problem));
                }
            }
        }
    }

    private Set<String> reachableFrom(ProcessDefinition process, String startId) {
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(startId);
        while (!todo.isEmpty()) {
            String id = todo.poll();
            if (seen.add(id)) {
                process.outgoing(id).forEach(e -> todo.add(e.to()));
            }
        }
        return seen;
    }
}

package io.dotflows.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A process: a graph of nodes joined by edges.
 *
 * @param layout canvas positions for the modeler; ignored by the engine
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessDefinition(String processKey, String name, List<Node> nodes, List<Edge> edges,
                                Map<String, Object> layout) {

    public ProcessDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public Optional<Node> findNode(String id) {
        return nodes.stream().filter(n -> n.id() != null && n.id().equals(id)).findFirst();
    }

    public Node node(String id) {
        return findNode(id).orElseThrow(() -> new IllegalStateException("No node '" + id + "' in process " + processKey));
    }

    public List<Edge> outgoing(String nodeId) {
        return edges.stream().filter(e -> nodeId.equals(e.from())).toList();
    }

    public List<Edge> incoming(String nodeId) {
        return edges.stream().filter(e -> nodeId.equals(e.to())).toList();
    }

    public Node startNode() {
        return nodes.stream().filter(n -> n.type() == NodeType.START).findFirst()
                .orElseThrow(() -> new IllegalStateException("Process " + processKey + " has no start node"));
    }
}

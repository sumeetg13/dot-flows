package io.dotflows.model;

/**
 * A problem found in a definition.
 *
 * @param elementId the node, edge, column or rule the problem belongs to; null when it concerns the whole definition
 */
public record ValidationError(String elementId, String message) {

    @Override
    public String toString() {
        return elementId == null ? message : elementId + ": " + message;
    }
}

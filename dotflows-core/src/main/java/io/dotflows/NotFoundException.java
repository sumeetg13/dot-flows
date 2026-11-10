package io.dotflows;

/** A definition, version or instance that was asked for does not exist. */
public class NotFoundException extends FlowException {

    public NotFoundException(String message) {
        super(message);
    }
}

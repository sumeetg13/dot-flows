package io.dotflows;

import io.dotflows.model.ValidationError;

import java.util.List;
import java.util.stream.Collectors;

/** A definition was rejected; {@link #errors()} lists every problem found. */
public class ValidationException extends FlowException {

    private final List<ValidationError> errors;

    public ValidationException(List<ValidationError> errors) {
        super("Definition is invalid: " + errors.stream().map(ValidationError::toString).collect(Collectors.joining("; ")));
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }
}

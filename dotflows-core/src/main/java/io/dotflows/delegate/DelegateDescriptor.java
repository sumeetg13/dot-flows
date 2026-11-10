package io.dotflows.delegate;

import java.util.Arrays;
import java.util.List;

/** What the modeler shows about a registered delegate. */
public record DelegateDescriptor(String name, String description, List<Input> inputs) {

    public record Input(String name, String description, boolean required) {
    }

    public static DelegateDescriptor of(FlowDelegate annotation) {
        return new DelegateDescriptor(annotation.value(), annotation.description(),
                Arrays.stream(annotation.inputs())
                        .map(i -> new Input(i.name(), i.description(), i.required()))
                        .toList());
    }
}

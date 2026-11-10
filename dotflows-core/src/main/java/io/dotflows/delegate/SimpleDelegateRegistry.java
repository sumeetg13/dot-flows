package io.dotflows.delegate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A registry filled by hand; the Spring Boot starter fills it from {@link FlowDelegate} beans. */
public class SimpleDelegateRegistry implements DelegateRegistry {

    private final Map<String, TaskDelegate> delegates = new ConcurrentHashMap<>();
    private final Map<String, DelegateDescriptor> descriptors = new ConcurrentHashMap<>();

    public SimpleDelegateRegistry register(String name, TaskDelegate delegate) {
        return register(new DelegateDescriptor(name, "", List.of()), delegate);
    }

    public SimpleDelegateRegistry register(DelegateDescriptor descriptor, TaskDelegate delegate) {
        if (delegates.putIfAbsent(descriptor.name(), delegate) != null) {
            throw new IllegalStateException("Two delegates are registered under the name '" + descriptor.name() + "'");
        }
        descriptors.put(descriptor.name(), descriptor);
        return this;
    }

    @Override
    public Optional<TaskDelegate> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(delegates.get(name));
    }

    @Override
    public List<DelegateDescriptor> list() {
        List<DelegateDescriptor> list = new ArrayList<>(descriptors.values());
        list.sort((a, b) -> a.name().compareTo(b.name()));
        return list;
    }
}

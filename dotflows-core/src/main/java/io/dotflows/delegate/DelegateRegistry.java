package io.dotflows.delegate;

import java.util.List;
import java.util.Optional;

/** Looks up delegates by the name used in process definitions. */
public interface DelegateRegistry {

    Optional<TaskDelegate> find(String name);

    List<DelegateDescriptor> list();
}

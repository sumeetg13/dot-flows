package io.dotflows.store;

import java.util.List;
import java.util.Optional;

/** Persistence for instances and their step history. All methods run inside the caller's transaction. */
public interface InstanceStore {

    void insert(InstanceRecord instance);

    void update(InstanceRecord instance);

    Optional<InstanceRecord> findById(String id);

    /** Like {@link #findById} but holds a row lock until the transaction ends. */
    Optional<InstanceRecord> lockById(String id);

    List<InstanceRecord> find(InstanceQuery query);

    void addStep(StepRecord step);

    /** Steps in the order they happened. */
    List<StepRecord> listSteps(String instanceId);
}

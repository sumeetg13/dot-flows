package io.dotflows.store;

import java.util.function.Supplier;

/** Runs engine work atomically against the stores. */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}

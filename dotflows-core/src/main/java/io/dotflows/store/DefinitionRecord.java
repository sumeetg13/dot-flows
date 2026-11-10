package io.dotflows.store;

import java.time.Instant;

/**
 * A stored process or decision definition: either the editable draft or one immutable deployed version.
 *
 * @param version 0 for the draft, 1..n for deployed versions
 * @param active  true for the one deployed version new instances start on
 */
public record DefinitionRecord(long id, Kind kind, String key, int version, Status status, boolean active,
                               String name, String json, Instant updatedAt) {

    public enum Kind {PROCESS, DECISION}

    public enum Status {DRAFT, DEPLOYED}
}

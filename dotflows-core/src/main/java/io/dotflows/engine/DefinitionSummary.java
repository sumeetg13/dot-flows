package io.dotflows.engine;

import java.time.Instant;

/**
 * One row of the process or decision list.
 *
 * @param activeVersion     null when nothing has been deployed yet
 * @param undeployedChanges true when the draft differs from the active version (or nothing is deployed)
 */
public record DefinitionSummary(String key, String name, Integer activeVersion, Instant deployedAt,
                                boolean undeployedChanges, Instant draftUpdatedAt) {
}

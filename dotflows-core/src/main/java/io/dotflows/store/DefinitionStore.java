package io.dotflows.store;

import io.dotflows.store.DefinitionRecord.Kind;

import java.util.List;
import java.util.Optional;

/** Persistence for drafts and deployed versions. All methods run inside the caller's transaction. */
public interface DefinitionStore {

    Optional<DefinitionRecord> findDraft(Kind kind, String key);

    /** Creates or replaces the single draft for this key. */
    DefinitionRecord saveDraft(Kind kind, String key, String name, String json);

    /** Stores the JSON as the next version and makes it the active one. */
    DefinitionRecord insertVersion(Kind kind, String key, String name, String json);

    Optional<DefinitionRecord> findActive(Kind kind, String key);

    Optional<DefinitionRecord> findVersion(Kind kind, String key, int version);

    Optional<DefinitionRecord> findById(long id);

    /** Deployed versions, newest first. */
    List<DefinitionRecord> listVersions(Kind kind, String key);

    /** Every draft and every active version of the given kind. */
    List<DefinitionRecord> listCurrent(Kind kind);

    /** @return false when that version does not exist */
    boolean activate(Kind kind, String key, int version);

    /** Removes the draft and every version. */
    void delete(Kind kind, String key);
}

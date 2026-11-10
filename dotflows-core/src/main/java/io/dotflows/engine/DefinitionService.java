package io.dotflows.engine;

import io.dotflows.FlowException;
import io.dotflows.NotFoundException;
import io.dotflows.ValidationException;
import io.dotflows.decision.DecisionEvaluator;
import io.dotflows.decision.DecisionTable;
import io.dotflows.model.DefinitionJson;
import io.dotflows.model.ProcessDefinition;
import io.dotflows.model.ValidationError;
import io.dotflows.store.DefinitionRecord;
import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.DefinitionStore;
import io.dotflows.store.InstanceQuery;
import io.dotflows.store.InstanceStore;
import io.dotflows.store.TransactionRunner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The lifecycle of process and decision definitions: edit a draft, validate it, deploy it as a new immutable
 * version, and choose which version is active.
 */
public class DefinitionService {

    private final DefinitionStore store;
    private final InstanceStore instances;
    private final TransactionRunner transactions;
    private final DefinitionJson json;
    private final ProcessValidator processValidator;
    private final DecisionEvaluator decisionEvaluator;

    public DefinitionService(DefinitionStore store, InstanceStore instances, TransactionRunner transactions,
                             DefinitionJson json, ProcessValidator processValidator,
                             DecisionEvaluator decisionEvaluator) {
        this.store = store;
        this.instances = instances;
        this.transactions = transactions;
        this.json = json;
        this.processValidator = processValidator;
        this.decisionEvaluator = decisionEvaluator;
    }

    /** Saves the working copy. It only has to be well-formed JSON with the right key; it may still be invalid. */
    public DefinitionRecord saveDraft(Kind kind, String key, String definitionJson) {
        Parsed parsed = parse(kind, definitionJson);
        if (!ProcessValidator.KEY.matcher(key).matches()) {
            throw new FlowException("Key must start with a letter and contain only letters, digits, '-' and '_'");
        }
        if (!key.equals(parsed.key())) {
            throw new FlowException("The definition's key is '" + parsed.key() + "' but it is being saved as '" + key + "'");
        }
        return transactions.inTransaction(() -> store.saveDraft(kind, key, parsed.name(), definitionJson));
    }

    public List<ValidationError> validate(Kind kind, String definitionJson) {
        Parsed parsed;
        try {
            parsed = parse(kind, definitionJson);
        } catch (FlowException e) {
            return List.of(new ValidationError(null, e.getMessage()));
        }
        return transactions.inTransaction(parsed::validate);
    }

    /** Deploys the current draft as the next version and makes it active. */
    public DefinitionRecord deployDraft(Kind kind, String key) {
        return transactions.inTransaction(() -> {
            DefinitionRecord draft = store.findDraft(kind, key)
                    .orElseThrow(() -> new NotFoundException("There is no draft of " + label(kind) + " '" + key + "'"));
            return deployChecked(kind, key, draft.json());
        });
    }

    /**
     * Deploys the given JSON directly, for definitions kept in source control. Does nothing and returns the
     * active version when it already has exactly this content.
     */
    public DefinitionRecord deploy(Kind kind, String definitionJson) {
        Parsed parsed = parse(kind, definitionJson);
        return transactions.inTransaction(() -> {
            Optional<DefinitionRecord> active = store.findActive(kind, parsed.key());
            if (active.isPresent() && sameContent(active.get().json(), definitionJson)) {
                return active.get();
            }
            DefinitionRecord deployed = deployChecked(kind, parsed.key(), definitionJson);
            store.saveDraft(kind, parsed.key(), parsed.name(), definitionJson);
            return deployed;
        });
    }

    /** Makes an existing version the one new instances start on; this is how to roll back. */
    public DefinitionRecord activate(Kind kind, String key, int version) {
        return transactions.inTransaction(() -> {
            if (!store.activate(kind, key, version)) {
                throw new NotFoundException(label(kind) + " '" + key + "' has no version " + version);
            }
            return store.findVersion(kind, key, version).orElseThrow();
        });
    }

    /** The draft, or when there is none the active version, so an editor always has something to open. */
    public DefinitionRecord workingCopy(Kind kind, String key) {
        return transactions.inTransaction(() -> store.findDraft(kind, key)
                .or(() -> store.findActive(kind, key))
                .orElseThrow(() -> new NotFoundException("No " + label(kind) + " '" + key + "'")));
    }

    public Optional<DefinitionRecord> active(Kind kind, String key) {
        return transactions.inTransaction(() -> store.findActive(kind, key));
    }

    public DefinitionRecord version(Kind kind, String key, int version) {
        return transactions.inTransaction(() -> store.findVersion(kind, key, version))
                .orElseThrow(() -> new NotFoundException(label(kind) + " '" + key + "' has no version " + version));
    }

    public List<DefinitionRecord> versions(Kind kind, String key) {
        return transactions.inTransaction(() -> store.listVersions(kind, key));
    }

    public List<DefinitionSummary> list(Kind kind) {
        List<DefinitionRecord> current = transactions.inTransaction(() -> store.listCurrent(kind));
        Map<String, DefinitionRecord> drafts = new LinkedHashMap<>();
        Map<String, DefinitionRecord> actives = new LinkedHashMap<>();
        for (DefinitionRecord record : current) {
            (record.status() == DefinitionRecord.Status.DRAFT ? drafts : actives).put(record.key(), record);
        }
        List<String> keys = new ArrayList<>(drafts.keySet());
        actives.keySet().stream().filter(k -> !drafts.containsKey(k)).forEach(keys::add);
        keys.sort(Comparator.naturalOrder());

        List<DefinitionSummary> summaries = new ArrayList<>();
        for (String key : keys) {
            DefinitionRecord draft = drafts.get(key);
            DefinitionRecord active = actives.get(key);
            boolean changes = draft != null && (active == null || !sameContent(active.json(), draft.json()));
            summaries.add(new DefinitionSummary(key,
                    draft != null ? draft.name() : active.name(),
                    active == null ? null : active.version(),
                    active == null ? null : active.updatedAt(),
                    changes,
                    draft == null ? null : draft.updatedAt()));
        }
        return summaries;
    }

    /** Removes a definition entirely. A process that has instances cannot be deleted. */
    public void delete(Kind kind, String key) {
        transactions.run(() -> {
            if (kind == Kind.PROCESS && !instances.find(new InstanceQuery(key, null, null, 1, 0)).isEmpty()) {
                throw new FlowException("Process '" + key + "' has instances and cannot be deleted");
            }
            store.delete(kind, key);
        });
    }

    private DefinitionRecord deployChecked(Kind kind, String key, String definitionJson) {
        Parsed parsed = parse(kind, definitionJson);
        List<ValidationError> errors = parsed.validate();
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return store.insertVersion(kind, key, parsed.name(), definitionJson);
    }

    private boolean sameContent(String a, String b) {
        return json.readMap(a).equals(json.readMap(b));
    }

    private Parsed parse(Kind kind, String definitionJson) {
        if (kind == Kind.PROCESS) {
            ProcessDefinition process = json.parseProcess(definitionJson);
            return new Parsed(process.processKey(), process.name(), () -> processValidator.validate(process));
        }
        DecisionTable table = json.parseDecision(definitionJson);
        return new Parsed(table.decisionKey(), table.name(), () -> decisionEvaluator.validate(table));
    }

    private static String label(Kind kind) {
        return kind == Kind.PROCESS ? "process" : "decision";
    }

    private record Parsed(String key, String name, java.util.function.Supplier<List<ValidationError>> validator) {
        List<ValidationError> validate() {
            return validator.get();
        }
    }
}

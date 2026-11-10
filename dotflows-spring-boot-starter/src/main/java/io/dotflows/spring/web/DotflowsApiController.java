package io.dotflows.spring.web;

import io.dotflows.FlowException;
import io.dotflows.decision.DecisionEvaluator;
import io.dotflows.decision.DecisionTable;
import io.dotflows.delegate.DelegateDescriptor;
import io.dotflows.delegate.DelegateRegistry;
import io.dotflows.engine.DefinitionService;
import io.dotflows.engine.DefinitionSummary;
import io.dotflows.engine.FlowEngine;
import io.dotflows.model.DefinitionJson;
import io.dotflows.model.ValidationError;
import io.dotflows.store.DefinitionRecord;
import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.InstanceQuery;
import io.dotflows.store.InstanceRecord;
import io.dotflows.store.StepRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The REST API used by the modeler UI. {@code {kind}} is {@code processes} or {@code decisions}.
 * These endpoints are not authenticated; the host application must protect them.
 */
@RestController
@RequestMapping("${dotflows.web.base-path:/dotflows}/api")
public class DotflowsApiController {

    private static final String KIND = "{kind:processes|decisions}";

    public record Definition(String key, int version, String status, boolean active, String name, Instant updatedAt,
                             Map<String, Object> definition) {
    }

    public record VersionInfo(int version, boolean active, String name, Instant deployedAt) {
    }

    public record Validation(List<ValidationError> errors) {
    }

    public record StartRequest(Map<String, Object> variables, String businessKey, Integer version) {
    }

    public record Started(String instanceId) {
    }

    public record EvaluateRequest(Map<String, Object> variables, Map<String, Object> definition) {
    }

    public record Evaluation(Object result) {
    }

    public record InstanceDetail(InstanceRecord instance, List<StepRecord> steps) {
    }

    private final DefinitionService definitions;
    private final FlowEngine engine;
    private final DelegateRegistry delegates;
    private final DecisionEvaluator decisions;
    private final DefinitionJson json;

    public DotflowsApiController(DefinitionService definitions, FlowEngine engine, DelegateRegistry delegates,
                                 DecisionEvaluator decisions, DefinitionJson json) {
        this.definitions = definitions;
        this.engine = engine;
        this.delegates = delegates;
        this.decisions = decisions;
        this.json = json;
    }

    // ---- definitions

    @GetMapping("/" + KIND)
    public List<DefinitionSummary> list(@PathVariable String kind) {
        return definitions.list(kind(kind));
    }

    /** The draft, or the active version when there is no draft. */
    @GetMapping("/" + KIND + "/{key}")
    public Definition workingCopy(@PathVariable String kind, @PathVariable String key) {
        return dto(definitions.workingCopy(kind(kind), key));
    }

    @PutMapping("/" + KIND + "/{key}/draft")
    public Definition saveDraft(@PathVariable String kind, @PathVariable String key, @RequestBody String body) {
        return dto(definitions.saveDraft(kind(kind), key, body));
    }

    @PostMapping("/" + KIND + "/validate")
    public Validation validate(@PathVariable String kind, @RequestBody String body) {
        return new Validation(definitions.validate(kind(kind), body));
    }

    @PostMapping("/" + KIND + "/{key}/deploy")
    public Definition deploy(@PathVariable String kind, @PathVariable String key) {
        return dto(definitions.deployDraft(kind(kind), key));
    }

    @GetMapping("/" + KIND + "/{key}/versions")
    public List<VersionInfo> versions(@PathVariable String kind, @PathVariable String key) {
        return definitions.versions(kind(kind), key).stream()
                .map(d -> new VersionInfo(d.version(), d.active(), d.name(), d.updatedAt())).toList();
    }

    @GetMapping("/" + KIND + "/{key}/versions/{version}")
    public Definition version(@PathVariable String kind, @PathVariable String key, @PathVariable int version) {
        return dto(definitions.version(kind(kind), key, version));
    }

    @PostMapping("/" + KIND + "/{key}/versions/{version}/activate")
    public Definition activate(@PathVariable String kind, @PathVariable String key, @PathVariable int version) {
        return dto(definitions.activate(kind(kind), key, version));
    }

    @DeleteMapping("/" + KIND + "/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String kind, @PathVariable String key) {
        definitions.delete(kind(kind), key);
    }

    /** Tries a decision table against sample variables: the posted definition if given, else the working copy. */
    @PostMapping("/decisions/{key}/evaluate")
    public Evaluation evaluate(@PathVariable String key, @RequestBody EvaluateRequest request) {
        String source = request.definition() != null
                ? json.write(request.definition())
                : definitions.workingCopy(Kind.DECISION, key).json();
        DecisionTable table = json.parseDecision(source);
        List<ValidationError> errors = decisions.validate(table);
        if (!errors.isEmpty()) {
            throw new io.dotflows.ValidationException(errors);
        }
        return new Evaluation(decisions.evaluate(table, request.variables() == null ? Map.of() : request.variables()));
    }

    // ---- instances

    @PostMapping("/processes/{key}/start")
    public Started start(@PathVariable String key, @RequestBody(required = false) StartRequest request) {
        StartRequest r = request == null ? new StartRequest(null, null, null) : request;
        String id = r.version() == null
                ? engine.start(key, r.variables(), r.businessKey())
                : engine.startVersion(key, r.version(), r.variables(), r.businessKey());
        return new Started(id);
    }

    @GetMapping("/instances")
    public List<InstanceRecord> instances(@RequestParam(required = false) String processKey,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String businessKey,
                                          @RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
        InstanceRecord.Status parsed;
        try {
            parsed = blank(status) ? null : InstanceRecord.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new FlowException("Unknown status '" + status + "'");
        }
        return engine.find(new InstanceQuery(blank(processKey) ? null : processKey, parsed,
                blank(businessKey) ? null : businessKey, limit, offset));
    }

    @GetMapping("/instances/{id}")
    public InstanceDetail instance(@PathVariable String id) {
        return new InstanceDetail(engine.instance(id), engine.steps(id));
    }

    @PostMapping("/instances/{id}/retry")
    public InstanceDetail retry(@PathVariable String id) {
        engine.retry(id);
        return instance(id);
    }

    @PostMapping("/instances/{id}/cancel")
    public InstanceDetail cancel(@PathVariable String id) {
        engine.cancel(id);
        return instance(id);
    }

    // ---- catalog

    @GetMapping("/delegates")
    public List<DelegateDescriptor> delegates() {
        return delegates.list();
    }

    private Definition dto(DefinitionRecord record) {
        return new Definition(record.key(), record.version(), record.status().name(), record.active(), record.name(),
                record.updatedAt(), json.readMap(record.json()));
    }

    private static Kind kind(String path) {
        return path.equals("processes") ? Kind.PROCESS : Kind.DECISION;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

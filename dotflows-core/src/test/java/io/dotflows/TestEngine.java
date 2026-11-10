package io.dotflows;

import io.dotflows.decision.DecisionEvaluator;
import io.dotflows.delegate.SimpleDelegateRegistry;
import io.dotflows.engine.DecisionTaskHandler;
import io.dotflows.engine.DefinitionService;
import io.dotflows.engine.DelegateTaskHandler;
import io.dotflows.engine.EngineOptions;
import io.dotflows.engine.FlowEngine;
import io.dotflows.engine.ProcessValidator;
import io.dotflows.engine.TaskHandler;
import io.dotflows.expr.ExpressionEvaluator;
import io.dotflows.model.DefinitionJson;
import io.dotflows.model.RetryPolicy;
import io.dotflows.store.memory.InMemoryStores;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** An engine wired to in-memory stores, with retries that are due immediately. */
public class TestEngine {

    public final InMemoryStores stores = new InMemoryStores();
    public final SimpleDelegateRegistry delegates = new SimpleDelegateRegistry();
    public final ExpressionEvaluator expressions = new ExpressionEvaluator();
    public final DefinitionJson json = new DefinitionJson();
    public final DecisionEvaluator decisions = new DecisionEvaluator(expressions);
    public final DefinitionService definitions;
    public final FlowEngine engine;

    public TestEngine() {
        Map<String, TaskHandler> handlers = new LinkedHashMap<>();
        handlers.put(DelegateTaskHandler.TYPE, new DelegateTaskHandler(delegates, expressions));
        handlers.put(DecisionTaskHandler.TYPE, new DecisionTaskHandler(stores, stores, decisions, json));
        definitions = new DefinitionService(stores, stores, stores, json,
                new ProcessValidator(handlers, expressions), decisions);
        engine = new FlowEngine(stores, stores, stores, stores, handlers, expressions, json,
                new EngineOptions(new RetryPolicy(3, 0), Duration.ofMinutes(5)), Clock.systemUTC());
    }
}

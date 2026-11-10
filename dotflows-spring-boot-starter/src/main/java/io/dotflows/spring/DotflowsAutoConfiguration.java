package io.dotflows.spring;

import io.dotflows.decision.DecisionEvaluator;
import io.dotflows.delegate.DelegateRegistry;
import io.dotflows.engine.DecisionTaskHandler;
import io.dotflows.engine.DefinitionService;
import io.dotflows.engine.DelegateTaskHandler;
import io.dotflows.engine.EngineOptions;
import io.dotflows.engine.FlowEngine;
import io.dotflows.engine.JobWorker;
import io.dotflows.engine.ProcessValidator;
import io.dotflows.engine.TaskHandler;
import io.dotflows.expr.ExpressionEvaluator;
import io.dotflows.model.DefinitionJson;
import io.dotflows.model.RetryPolicy;
import io.dotflows.spring.jdbc.JdbcDefinitionStore;
import io.dotflows.spring.jdbc.JdbcInstanceStore;
import io.dotflows.spring.jdbc.JdbcJobStore;
import io.dotflows.store.DefinitionStore;
import io.dotflows.store.InstanceStore;
import io.dotflows.store.JobStore;
import io.dotflows.store.TransactionRunner;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
@EnableConfigurationProperties(DotflowsProperties.class)
public class DotflowsAutoConfiguration {

    /** Marker bean: other dot-flows beans depend on it so the tables exist before anything queries them. */
    public static final class SchemaReady {
    }

    @Bean
    SchemaReady dotflowsSchema(DataSource dataSource, DotflowsProperties properties) {
        if (properties.isInitializeSchema()) {
            new ResourceDatabasePopulator(new ClassPathResource("io/dotflows/schema/schema.sql")).execute(dataSource);
        }
        return new SchemaReady();
    }

    @Bean
    @ConditionalOnMissingBean
    ExpressionEvaluator dotflowsExpressionEvaluator() {
        return new ExpressionEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    DefinitionJson dotflowsDefinitionJson() {
        return new DefinitionJson();
    }

    @Bean
    @ConditionalOnMissingBean
    DecisionEvaluator dotflowsDecisionEvaluator(ExpressionEvaluator expressions) {
        return new DecisionEvaluator(expressions);
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionRunner dotflowsTransactionRunner(PlatformTransactionManager transactionManager) {
        return new SpringTransactionRunner(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    DefinitionStore dotflowsDefinitionStore(DataSource dataSource, SchemaReady schema) {
        return new JdbcDefinitionStore(JdbcClient.create(dataSource), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    InstanceStore dotflowsInstanceStore(DataSource dataSource, DefinitionJson json, SchemaReady schema) {
        return new JdbcInstanceStore(JdbcClient.create(dataSource), json);
    }

    @Bean
    @ConditionalOnMissingBean
    JobStore dotflowsJobStore(DataSource dataSource, SchemaReady schema) {
        return new JdbcJobStore(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    DelegateRegistry dotflowsDelegateRegistry(ListableBeanFactory beanFactory) {
        return new SpringDelegateRegistry(beanFactory);
    }

    @Bean
    DelegateTaskHandler dotflowsDelegateTaskHandler(DelegateRegistry registry, ExpressionEvaluator expressions) {
        return new DelegateTaskHandler(registry, expressions);
    }

    @Bean
    DecisionTaskHandler dotflowsDecisionTaskHandler(DefinitionStore definitions, TransactionRunner transactions,
                                                    DecisionEvaluator evaluator, DefinitionJson json) {
        return new DecisionTaskHandler(definitions, transactions, evaluator, json);
    }

    @Bean
    HttpTaskHandler dotflowsHttpTaskHandler(ExpressionEvaluator expressions, DefinitionJson json,
                                            DotflowsProperties properties) {
        return new HttpTaskHandler(expressions, json, properties.getHttp());
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessValidator dotflowsProcessValidator(List<TaskHandler> handlers, ExpressionEvaluator expressions) {
        return new ProcessValidator(byType(handlers), expressions);
    }

    @Bean
    @ConditionalOnMissingBean
    DefinitionService dotflowsDefinitionService(DefinitionStore definitions, InstanceStore instances,
                                                TransactionRunner transactions, DefinitionJson json,
                                                ProcessValidator validator, DecisionEvaluator decisions) {
        return new DefinitionService(definitions, instances, transactions, json, validator, decisions);
    }

    @Bean
    @ConditionalOnMissingBean
    FlowEngine dotflowsEngine(DefinitionStore definitions, InstanceStore instances, JobStore jobs,
                              TransactionRunner transactions, List<TaskHandler> handlers,
                              ExpressionEvaluator expressions, DefinitionJson json, DotflowsProperties properties) {
        EngineOptions options = new EngineOptions(
                new RetryPolicy(properties.getRetry().getMaxAttempts(), properties.getRetry().getBackoff().toSeconds()),
                properties.getWorker().getLockDuration());
        return new FlowEngine(definitions, instances, jobs, transactions, byType(handlers), expressions, json,
                options, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "dotflows.worker", name = "enabled", matchIfMissing = true)
    SmartLifecycle dotflowsWorker(FlowEngine engine, DotflowsProperties properties) {
        DotflowsProperties.Worker config = properties.getWorker();
        JobWorker worker = new JobWorker(engine, config.getThreads(), config.getPollInterval());
        return new SmartLifecycle() {
            @Override
            public void start() {
                worker.start();
            }

            @Override
            public void stop() {
                worker.stop(config.getShutdownGrace());
            }

            @Override
            public boolean isRunning() {
                return worker.isRunning();
            }
        };
    }

    private static Map<String, TaskHandler> byType(List<TaskHandler> handlers) {
        Map<String, TaskHandler> map = new LinkedHashMap<>();
        for (TaskHandler handler : handlers) {
            if (map.put(handler.taskType(), handler) != null) {
                throw new IllegalStateException("Two TaskHandler beans handle task type '" + handler.taskType() + "'");
            }
        }
        return map;
    }
}

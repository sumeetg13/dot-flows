package io.dotflows.spring.web;

import io.dotflows.decision.DecisionEvaluator;
import io.dotflows.delegate.DelegateRegistry;
import io.dotflows.engine.DefinitionService;
import io.dotflows.engine.FlowEngine;
import io.dotflows.model.DefinitionJson;
import io.dotflows.spring.DotflowsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import io.dotflows.spring.DotflowsAutoConfiguration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The REST API and the modeler UI; present only in servlet web applications. */
@AutoConfiguration(after = DotflowsAutoConfiguration.class)
@ConditionalOnBean(FlowEngine.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "dotflows.web", name = "enabled", matchIfMissing = true)
public class DotflowsWebConfiguration {

    @Bean
    DotflowsApiController dotflowsApiController(DefinitionService definitions, FlowEngine engine,
                                                DelegateRegistry delegates, DecisionEvaluator decisions,
                                                DefinitionJson json) {
        return new DotflowsApiController(definitions, engine, delegates, decisions, json);
    }

    @Bean
    DotflowsApiExceptionHandler dotflowsApiExceptionHandler() {
        return new DotflowsApiExceptionHandler();
    }

    @Bean
    DotflowsUiController dotflowsUiController() {
        return new DotflowsUiController();
    }

    @Bean
    WebMvcConfigurer dotflowsUiResources(DotflowsProperties properties) {
        String basePath = properties.getWeb().getBasePath().replaceAll("/+$", "");
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(basePath + "/**").addResourceLocations("classpath:/io/dotflows/ui/");
            }
        };
    }
}

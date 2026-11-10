package io.dotflows.sample;

import io.dotflows.engine.DefinitionService;
import io.dotflows.store.DefinitionRecord.Kind;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

/**
 * A host application: it adds the starter, a datasource and its own delegates, and gets the engine,
 * the REST API and the modeler at http://localhost:8080/dotflows.
 */
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }

    /**
     * Definitions kept in source control can be deployed at startup. Deploying identical content again does
     * nothing, so this is safe on every boot; decisions go first because the process refers to one.
     */
    @Bean
    ApplicationRunner deployBundledFlows(DefinitionService definitions) {
        return args -> {
            definitions.deploy(Kind.DECISION, read("flows/notify-channel.decision.json"));
            definitions.deploy(Kind.PROCESS, read("flows/order-notification.process.json"));
        };
    }

    private static String read(String path) throws java.io.IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}

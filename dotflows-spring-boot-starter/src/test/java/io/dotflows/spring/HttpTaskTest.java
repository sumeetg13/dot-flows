package io.dotflows.spring;

import com.sun.net.httpserver.HttpServer;
import io.dotflows.engine.DefinitionService;
import io.dotflows.engine.FlowEngine;
import io.dotflows.model.ValidationError;
import io.dotflows.store.DefinitionRecord.Kind;
import io.dotflows.store.InstanceRecord;
import io.dotflows.store.InstanceRecord.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:http;DB_CLOSE_DELAY=-1",
        "dotflows.worker.enabled=false",
        "dotflows.retry.max-attempts=1",
        "dotflows.http.variables.sms-key=s3cret"
})
class HttpTaskTest {

    static HttpServer server;
    static final List<String> received = new CopyOnWriteArrayList<>();

    @Autowired
    FlowEngine engine;
    @Autowired
    DefinitionService definitions;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sms", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            byte[] response = "{\"id\": \"sms-42\", \"queued\": true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void sendsRenderedRequestAndStoresJsonResponse() {
        definitions.deploy(Kind.PROCESS, Definitions.singleTask("sms", "http", """
                {"method": "POST", "url": "%s",
                 "headers": {"Authorization": "Bearer ${sms-key}"},
                 "body": {"to": "#{#phone}", "text": "Hi #{#name}", "priority": 1},
                 "resultVariable": "sms"}
                """.formatted(url("/sms?ref=#{#ref}"))));

        // A variable that looks like a placeholder must be sent as-is, never resolved to the secret.
        String id = engine.start("sms", Map.of("phone", "+100", "name", "${sms-key}", "ref", "r1"));
        engine.drain();

        InstanceRecord instance = engine.instance(id);
        assertThat(instance.status()).as(String.valueOf(instance.error())).isEqualTo(Status.COMPLETED);
        assertThat(received).containsExactly(
                "POST /sms?ref=r1 Bearer s3cret {\"to\":\"+100\",\"text\":\"Hi ${sms-key}\",\"priority\":1}");
        assertThat(instance.variables().get("sms"))
                .isEqualTo(Map.of("status", 200, "body", Map.of("id", "sms-42", "queued", true)));
    }

    @Test
    void errorStatusFailsTheTaskUnlessToldOtherwise() {
        definitions.deploy(Kind.PROCESS, Definitions.singleTask("down", "http",
                "{\"url\": \"%s\"}".formatted(url("/down"))));
        String failed = engine.start("down", null);
        engine.drain();
        assertThat(engine.instance(failed).status()).isEqualTo(Status.FAILED);
        assertThat(engine.instance(failed).error()).contains("HTTP 503");

        definitions.deploy(Kind.PROCESS, Definitions.singleTask("tolerant", "http",
                "{\"url\": \"%s\", \"failOnErrorStatus\": false, \"resultVariable\": \"r\"}".formatted(url("/down"))));
        String ok = engine.start("tolerant", null);
        engine.drain();
        assertThat(engine.instance(ok).status()).isEqualTo(Status.COMPLETED);
        assertThat(engine.instance(ok).variables().get("r")).isEqualTo(new java.util.HashMap<>() {{
            put("status", 503);
            put("body", null);
        }});
    }

    @Test
    void validationRejectsUnknownSecretsAndBadTemplates() {
        List<ValidationError> errors = definitions.validate(Kind.PROCESS, Definitions.singleTask("bad", "http",
                "{\"method\": \"TRACE\", \"url\": \"http://x/#{#a +}\", \"headers\": {\"X\": \"${nope}\"}}"));
        assertThat(errors).extracting(ValidationError::message)
                .anyMatch(m -> m.contains("unsupported HTTP method 'TRACE'"))
                .anyMatch(m -> m.contains("is not a valid template"))
                .anyMatch(m -> m.contains("${nope} is not defined under dotflows.http.variables"));
    }
}

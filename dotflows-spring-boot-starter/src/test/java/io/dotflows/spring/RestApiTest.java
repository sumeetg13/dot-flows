package io.dotflows.spring;

import io.dotflows.engine.FlowEngine;
import io.dotflows.store.InstanceRecord.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole stack on H2: REST API, JDBC stores, background worker. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rest;DB_CLOSE_DELAY=-1",
        "dotflows.worker.poll-interval=50ms",
        "dotflows.retry.backoff=0s"
})
@AutoConfigureMockMvc
class RestApiTest {

    private static final String API = "/dotflows/api";

    @Autowired
    MockMvc mvc;
    @Autowired
    FlowEngine engine;
    @Autowired
    TestApp.SendEmail sendEmail;
    @Autowired
    TestApp.FailUntilThird failUntilThird;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void modelDeployRunAndRollBack() throws Exception {
        mvc.perform(get(API + "/delegates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'sendEmail')].inputs[0].name").value("to"));

        // An invalid draft can be saved, is reported by validate, and cannot be deployed.
        String broken = Definitions.emailProcess("welcome", "").replace("sendEmail", "ghost");
        mvc.perform(put(API + "/processes/welcome/draft").contentType(MediaType.APPLICATION_JSON).content(broken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(post(API + "/processes/validate").contentType(MediaType.APPLICATION_JSON).content(broken))
                .andExpect(jsonPath("$.errors[0].elementId").value("email"));
        mvc.perform(post(API + "/processes/welcome/deploy"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message").value("delegate 'ghost' is not registered in this application"));

        // Version 1.
        mvc.perform(put(API + "/processes/welcome/draft").contentType(MediaType.APPLICATION_JSON)
                        .content(Definitions.emailProcess("welcome", "#user.email")))
                .andExpect(status().isOk());
        mvc.perform(post(API + "/processes/welcome/deploy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.definition.nodes[1].config.delegate").value("sendEmail"));

        String started = mvc.perform(post(API + "/processes/welcome/start").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\": {\"user\": {\"email\": \"ada@example.com\"}}, \"businessKey\": \"u-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(started).get("instanceId").asString();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(engine.instance(id).status()).isEqualTo(Status.COMPLETED));
        assertThat(sendEmail.sent).contains("ada@example.com");

        mvc.perform(get(API + "/instances/" + id))
                .andExpect(jsonPath("$.instance.status").value("COMPLETED"))
                .andExpect(jsonPath("$.instance.variables.emailed").value(true))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[1].nodeId").value("email"));
        mvc.perform(get(API + "/instances").param("businessKey", "u-1"))
                .andExpect(jsonPath("$.length()").value(1));

        // Version 2, then roll back to 1.
        mvc.perform(put(API + "/processes/welcome/draft").contentType(MediaType.APPLICATION_JSON)
                        .content(Definitions.emailProcess("welcome", "'fixed@example.com'")))
                .andExpect(status().isOk());
        mvc.perform(get(API + "/processes"))
                .andExpect(jsonPath("$[?(@.key == 'welcome')].undeployedChanges").value(true));
        mvc.perform(post(API + "/processes/welcome/deploy")).andExpect(jsonPath("$.version").value(2));
        mvc.perform(post(API + "/processes/welcome/versions/1/activate")).andExpect(jsonPath("$.active").value(true));

        JsonNode versions = mapper.readTree(mvc.perform(get(API + "/processes/welcome/versions"))
                .andReturn().getResponse().getContentAsString());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(versions.get(0).get("active").asBoolean()).isFalse();
        assertThat(versions.get(1).get("active").asBoolean()).isTrue();

        mvc.perform(get(API + "/processes/missing")).andExpect(status().isNotFound());
        mvc.perform(post(API + "/processes/missing/start")).andExpect(status().isNotFound());
    }

    @Test
    void workerRetriesFailingTasks() throws Exception {
        mvc.perform(put(API + "/processes/flaky/draft").contentType(MediaType.APPLICATION_JSON)
                        .content(Definitions.singleTask("flaky", "delegate", "{\"delegate\": \"failUntilThird\"}")))
                .andExpect(status().isOk());
        mvc.perform(post(API + "/processes/flaky/deploy")).andExpect(status().isOk());

        String id = engine.start("flaky", null);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(engine.instance(id).status()).isEqualTo(Status.COMPLETED));
        assertThat(failUntilThird.calls).hasValue(3);
        assertThat(engine.steps(id)).hasSize(5);
    }

    @Test
    void decisionsCanBeTestedBeforeDeploying() throws Exception {
        String table = """
                {"decisionKey": "tier", "inputs": [{"id": "total", "expression": "#total"}],
                 "outputs": [{"name": "tier"}],
                 "rules": [{"when": {"total": {"op": ">", "value": 100}}, "then": {"tier": "gold"}},
                           {"then": {"tier": "basic"}}]}
                """;
        mvc.perform(put(API + "/decisions/tier/draft").contentType(MediaType.APPLICATION_JSON).content(table))
                .andExpect(status().isOk());
        mvc.perform(post(API + "/decisions/tier/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\": {\"total\": 500}}"))
                .andExpect(jsonPath("$.result").value("gold"));
        mvc.perform(post(API + "/decisions/tier/deploy")).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void basePathRedirectsToTheUi() throws Exception {
        mvc.perform(get("/dotflows")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/dotflows/"));
    }
}

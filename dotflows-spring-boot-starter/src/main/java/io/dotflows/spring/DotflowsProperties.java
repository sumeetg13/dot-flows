package io.dotflows.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("dotflows")
public class DotflowsProperties {

    /** Create the dflow_* tables at startup if they do not exist. Turn off to manage the schema yourself. */
    private boolean initializeSchema = true;

    private final Worker worker = new Worker();
    private final Retry retry = new Retry();
    private final Web web = new Web();
    private final Http http = new Http();

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public Worker getWorker() {
        return worker;
    }

    public Retry getRetry() {
        return retry;
    }

    public Web getWeb() {
        return web;
    }

    public Http getHttp() {
        return http;
    }

    public static class Worker {

        /** Execute jobs in this application. Turn off for nodes that should only start instances or serve the UI. */
        private boolean enabled = true;

        /** How many tasks may run at the same time in this application. */
        private int threads = 4;

        /** How often to look for due jobs when idle. */
        private Duration pollInterval = Duration.ofSeconds(1);

        /** How long a job stays reserved for the worker running it. Must exceed your slowest task. */
        private Duration lockDuration = Duration.ofMinutes(5);

        /** How long shutdown waits for running tasks to finish. */
        private Duration shutdownGrace = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getLockDuration() {
            return lockDuration;
        }

        public void setLockDuration(Duration lockDuration) {
            this.lockDuration = lockDuration;
        }

        public Duration getShutdownGrace() {
            return shutdownGrace;
        }

        public void setShutdownGrace(Duration shutdownGrace) {
            this.shutdownGrace = shutdownGrace;
        }
    }

    public static class Retry {

        /** Attempts per task, including the first, for tasks without their own retry policy. */
        private int maxAttempts = 3;

        /** Wait between attempts. */
        private Duration backoff = Duration.ofSeconds(10);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBackoff() {
            return backoff;
        }

        public void setBackoff(Duration backoff) {
            this.backoff = backoff;
        }
    }

    public static class Web {

        /** Expose the REST API and the modeler UI. They are NOT authenticated: protect the base path yourself. */
        private boolean enabled = true;

        /** Where the UI is served; the REST API lives under {base-path}/api. */
        private String basePath = "/dotflows";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }

    public static class Http {

        /** Values HTTP tasks may reference as ${name}, typically API keys. No other property is reachable. */
        private Map<String, String> variables = new LinkedHashMap<>();

        /** Hosts HTTP tasks may call. Empty means any host. */
        private List<String> allowedHosts = new ArrayList<>();

        /** Timeout for HTTP tasks that do not set timeoutSeconds. */
        private Duration defaultTimeout = Duration.ofSeconds(30);

        public Map<String, String> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, String> variables) {
            this.variables = variables;
        }

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts;
        }

        public Duration getDefaultTimeout() {
            return defaultTimeout;
        }

        public void setDefaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }
    }
}

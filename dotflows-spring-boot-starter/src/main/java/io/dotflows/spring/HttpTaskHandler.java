package io.dotflows.spring;

import io.dotflows.FlowException;
import io.dotflows.engine.TaskExecution;
import io.dotflows.engine.TaskHandler;
import io.dotflows.expr.ExpressionEvaluator;
import io.dotflows.model.DefinitionJson;
import io.dotflows.model.Node;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task type {@code http}: calls an HTTP API.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code method} (default GET) and {@code url}</li>
 *   <li>{@code headers}: name to value</li>
 *   <li>{@code body}: text, or a JSON object/array sent as JSON</li>
 *   <li>{@code timeoutSeconds}</li>
 *   <li>{@code resultVariable}: receives {@code {status, body}}; a JSON response body is parsed</li>
 *   <li>{@code failOnErrorStatus} (default true): a 4xx/5xx response fails the task so it is retried</li>
 * </ul>
 * In url, headers and body, {@code #{expression}} inserts process variables and {@code ${name}} inserts a value
 * from {@code dotflows.http.variables}, which is where API keys belong.
 */
public class HttpTaskHandler implements TaskHandler {

    public static final String TYPE = "http";

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private final ExpressionEvaluator expressions;
    private final DefinitionJson json;
    private final DotflowsProperties.Http properties;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HttpTaskHandler(ExpressionEvaluator expressions, DefinitionJson json, DotflowsProperties.Http properties) {
        this.expressions = expressions;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public String taskType() {
        return TYPE;
    }

    @Override
    public void validate(Node node, Consumer<String> problems) {
        String method = method(node);
        if (!METHODS.contains(method)) {
            problems.accept("unsupported HTTP method '" + method + "'");
        }
        String url = node.configString("url");
        if (url == null || url.isBlank()) {
            problems.accept("url is required");
        }
        List<String> texts = new ArrayList<>();
        if (url != null) {
            texts.add(url);
        }
        node.configMap("headers").values().forEach(v -> collectText(v, texts));
        collectText(node.config().get("body"), texts);
        for (String text : texts) {
            String problem = expressions.checkTemplate(text);
            if (problem != null) {
                problems.accept("'" + abbreviate(text) + "' is not a valid template: " + problem);
            }
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                if (!properties.getVariables().containsKey(matcher.group(1))) {
                    problems.accept("${" + matcher.group(1) + "} is not defined under dotflows.http.variables");
                }
            }
        }
    }

    @Override
    public Map<String, Object> execute(TaskExecution execution) {
        Node node = execution.node();
        Map<String, Object> variables = execution.variables();

        URI uri = URI.create(resolve(node.configString("url"), variables));
        checkAllowed(uri);

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout(node));
        RestClient.RequestBodySpec request = RestClient.builder().requestFactory(factory).build()
                .method(HttpMethod.valueOf(method(node)))
                .uri(uri);

        boolean contentTypeSet = false;
        for (Map.Entry<String, Object> header : node.configMap("headers").entrySet()) {
            if (header.getValue() != null) {
                request.header(header.getKey(), resolve(header.getValue().toString(), variables));
                contentTypeSet |= HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(header.getKey());
            }
        }

        Object body = node.config().get("body");
        if (body instanceof String text) {
            if (!text.isBlank()) {
                if (!contentTypeSet) {
                    request.contentType(MediaType.TEXT_PLAIN);
                }
                request.body(resolve(text, variables));
            }
        } else if (body != null) {
            if (!contentTypeSet) {
                request.contentType(MediaType.APPLICATION_JSON);
            }
            request.body(json.write(resolveDeep(body, variables)));
        }

        ResponseEntity<String> response = request.exchange((req, res) -> {
            String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.status(res.getStatusCode()).headers(res.getHeaders()).body(text);
        });

        int status = response.getStatusCode().value();
        if (status >= 400 && !Boolean.FALSE.equals(node.config().get("failOnErrorStatus"))) {
            throw new FlowException("HTTP " + status + " from " + uri.getHost() + ": " + abbreviate(response.getBody()));
        }

        Map<String, Object> updates = new HashMap<>();
        String resultVariable = node.configString("resultVariable");
        if (resultVariable != null && !resultVariable.isBlank()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("body", parseBody(response));
            updates.put(resultVariable, result);
        }
        return updates;
    }

    private Object parseBody(ResponseEntity<String> response) {
        String text = response.getBody();
        if (text == null || text.isBlank()) {
            return null;
        }
        MediaType type = response.getHeaders().getContentType();
        boolean isJson = type != null && (type.isCompatibleWith(MediaType.APPLICATION_JSON)
                || type.getSubtype().endsWith("+json"));
        if (!isJson) {
            return text;
        }
        String trimmed = text.trim();
        try {
            // Wrapping lets one parser handle objects, arrays and bare values alike.
            return json.readMap("{\"value\":" + trimmed + "}").get("value");
        } catch (FlowException e) {
            return text;
        }
    }

    private void checkAllowed(URI uri) {
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new FlowException("'" + uri + "' is not an absolute http(s) URL");
        }
        List<String> allowed = properties.getAllowedHosts();
        if (!allowed.isEmpty() && allowed.stream().noneMatch(h -> h.equalsIgnoreCase(uri.getHost()))) {
            throw new FlowException("Host '" + uri.getHost() + "' is not listed in dotflows.http.allowed-hosts");
        }
    }

    /**
     * Substitutes ${name} only where the definition itself wrote it: the text between placeholders is rendered
     * separately, so a value coming from a process variable is never scanned for placeholders and cannot pull
     * in a secret.
     */
    private String resolve(String template, Map<String, Object> variables) {
        StringBuilder result = new StringBuilder();
        int from = 0;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            result.append(expressions.render(template.substring(from, matcher.start()), variables));
            String value = properties.getVariables().get(matcher.group(1));
            if (value == null) {
                throw new FlowException("${" + matcher.group(1) + "} is not defined under dotflows.http.variables");
            }
            result.append(value);
            from = matcher.end();
        }
        result.append(expressions.render(template.substring(from), variables));
        return result.toString();
    }

    private Object resolveDeep(Object value, Map<String, Object> variables) {
        if (value instanceof String text) {
            return resolve(text, variables);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), resolveDeep(v, variables)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> resolveDeep(v, variables)).toList();
        }
        return value;
    }

    private void collectText(Object value, List<String> texts) {
        if (value instanceof String text) {
            texts.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectText(v, texts));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collectText(v, texts));
        }
    }

    private Duration timeout(Node node) {
        Object value = node.config().get("timeoutSeconds");
        if (value instanceof Number number && number.longValue() > 0) {
            return Duration.ofSeconds(number.longValue());
        }
        return properties.getDefaultTimeout();
    }

    private static String method(Node node) {
        String method = node.configString("method");
        return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}

package io.dotflows.spring;

final class Definitions {

    private Definitions() {
    }

    static String emailProcess(String key, String toExpression) {
        return """
                {
                  "processKey": "%s",
                  "name": "Send an email",
                  "nodes": [
                    {"id": "start", "type": "start"},
                    {"id": "email", "type": "task", "taskType": "delegate",
                     "config": {"delegate": "sendEmail", "inputs": {"to": "%s"}}},
                    {"id": "end", "type": "end"}
                  ],
                  "edges": [{"from": "start", "to": "email"}, {"from": "email", "to": "end"}]
                }
                """.formatted(key, toExpression);
    }

    static String singleTask(String key, String taskType, String configJson) {
        return """
                {
                  "processKey": "%s",
                  "nodes": [
                    {"id": "start", "type": "start"},
                    {"id": "work", "type": "task", "taskType": "%s", "config": %s},
                    {"id": "end", "type": "end"}
                  ],
                  "edges": [{"from": "start", "to": "work"}, {"from": "work", "to": "end"}]
                }
                """.formatted(key, taskType, configJson);
    }
}

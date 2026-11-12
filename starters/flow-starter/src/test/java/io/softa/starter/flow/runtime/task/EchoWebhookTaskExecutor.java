package io.softa.starter.flow.runtime.task;

import java.util.LinkedHashMap;
import java.util.Map;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * A CALL_WEBHOOK executor that calls nothing and echoes its resolved input, for tests.
 */
public class EchoWebhookTaskExecutor implements TaskExecutor {

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.CALL_WEBHOOK;
    }

    @Override
    public String getName() {
        return "Echo Webhook";
    }

    @Override
    public String getDescription() {
        return "Echoes resolved task input for service task integration testing.";
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>(request.getInput());
        result.put("executor", "echoWebhook");
        result.put("input", request.getInput());
        result.put("flowVariables", Map.copyOf(variables));
        return result;
    }
}

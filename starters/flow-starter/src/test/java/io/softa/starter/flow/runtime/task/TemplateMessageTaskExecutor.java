package io.softa.starter.flow.runtime.task;

import java.util.LinkedHashMap;
import java.util.Map;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Sample message-task executor that renders a message preview.
 * Used only for testing.
 */
public class TemplateMessageTaskExecutor implements TaskExecutor {

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.SEND_EMAIL;
    }

    @Override
    public String getExecutor() {
        return "templateMessage";
    }

    @Override
    public String getName() {
        return "Template Message";
    }

    @Override
    public String getDescription() {
        return "Builds a simple message preview from task input.";
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object recipient = request.getInput().getOrDefault("recipient", variables.get("recipient"));
        Object body = request.getInput().getOrDefault("body", "");
        result.put("recipient", recipient);
        result.put("body", body);
        result.put("preview", recipient + ": " + body);
        return result;
    }
}

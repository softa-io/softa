package io.softa.starter.flow.runtime.task;

import java.util.LinkedHashMap;
import java.util.Map;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Sample service-task executor that echoes resolved input and options.
 * Used only for testing.
 */
public class EchoServiceTaskExecutor implements TaskExecutor {

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.CALL_SERVICE;
    }

    @Override
    public String getExecutor() {
        return "echoService";
    }

    @Override
    public String getName() {
        return "Echo Service";
    }

    @Override
    public String getDescription() {
        return "Echoes resolved task input for service task integration testing.";
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>(request.getInput());
        result.put("executor", getExecutor());
        result.put("input", request.getInput());
        result.put("options", request.getOptions());
        result.put("flowVariables", Map.copyOf(variables));
        return result;
    }
}

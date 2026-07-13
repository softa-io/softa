package io.softa.starter.flow.runtime.task;

import java.util.LinkedHashMap;
import java.util.Map;

import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Sample data-task executor that returns a deterministic lookup payload.
 * Used only for testing.
 */
public class RecordLookupDataTaskExecutor implements TaskExecutor {

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.GET_RECORD;
    }

    @Override
    public String getExecutor() {
        return "recordLookup";
    }

    @Override
    public String getName() {
        return "Record Lookup";
    }

    @Override
    public String getDescription() {
        return "Returns a simple deterministic record payload from task input.";
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", request.getInput().getOrDefault("id", variables.get("id")));
        result.put("found", true);
        result.put("source", getExecutor());
        return result;
    }
}

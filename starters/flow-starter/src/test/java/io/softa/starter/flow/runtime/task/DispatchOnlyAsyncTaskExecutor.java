package io.softa.starter.flow.runtime.task;

import java.util.Map;

import io.softa.starter.flow.enums.FlowNodeType;

/** An AsyncTask executor that dispatches nothing: the instance waits until the test calls back. */
public class DispatchOnlyAsyncTaskExecutor implements TaskExecutor {

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.ASYNC_TASK;
    }

    @Override
    public String getName() {
        return "Async Task";
    }

    @Override
    public String getDescription() {
        return "Dispatches nothing";
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        return Map.of();
    }
}

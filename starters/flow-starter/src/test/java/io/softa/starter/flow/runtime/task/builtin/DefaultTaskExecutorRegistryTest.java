package io.softa.starter.flow.runtime.task.builtin;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.flow.runtime.task.TaskExecutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTaskExecutorRegistryTest {

    @Test
    void rejectsExecutorWhoseNodeTypeHasNoTypedConfigEntry() {
        // SCRIPT is not a task node type, so it has no TaskConfigTypes entry — an executor
        // targeting it would silently escape compile-time config validation.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new DefaultTaskExecutorRegistry(List.of(stubExecutor(FlowNodeType.SCRIPT))));
        assertTrue(thrown.getMessage().contains("TaskConfigTypes"));
    }

    @Test
    void acceptsExecutorForTypedTaskNodeType() {
        assertDoesNotThrow(() -> new DefaultTaskExecutorRegistry(List.of(stubExecutor(FlowNodeType.CREATE_RECORD))));
    }

    private static TaskExecutor stubExecutor(FlowNodeType nodeType) {
        return new TaskExecutor() {
            @Override
            public FlowNodeType getSupportedNodeType() {
                return nodeType;
            }

            @Override
            public String getExecutor() {
                return "Stub" + nodeType.getType();
            }

            @Override
            public String getName() {
                return "Stub";
            }

            @Override
            public String getDescription() {
                return "Stub";
            }

            @Override
            public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
                return Map.of();
            }
        };
    }
}

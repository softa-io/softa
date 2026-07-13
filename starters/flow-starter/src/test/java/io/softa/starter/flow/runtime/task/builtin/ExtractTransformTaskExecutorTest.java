package io.softa.starter.flow.runtime.task.builtin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExtractTransformTaskExecutor} — extracts a deduplicated field value
 * set from a collection variable.
 */
class ExtractTransformTaskExecutorTest {

    private final ExtractTransformTaskExecutor executor = new ExtractTransformTaskExecutor();

    @Test
    void supportsTransformNodeType() {
        assertEquals(FlowNodeType.TRANSFORM, executor.getSupportedNodeType());
        assertEquals("ExtractTransform", executor.getExecutor());
    }

    @Test
    void extractsDeduplicatedValuesViaPlaceholderVariable() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.TRANSFORM)
                .input(Map.of("collectionVariable", "{{ employeeList }}", "itemKey", "deptId"))
                .build();

        Map<String, Object> variables = Map.of("employeeList", List.of(
                Map.of("name", "alice", "deptId", 10),
                Map.of("name", "bob", "deptId", 20),
                Map.of("name", "carol", "deptId", 10)));

        Map<String, Object> output = executor.execute(request, variables);

        Object result = output.get("result");
        assertInstanceOf(Set.class, result);
        assertEquals(Set.of(10, 20), result);
    }

    @Test
    void resolvesByDirectVariableKeyWhenNotAPlaceholder() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.TRANSFORM)
                .input(Map.of("collectionVariable", "rows", "itemKey", "id"))
                .build();

        Map<String, Object> variables = Map.of("rows", List.of(
                Map.of("id", "a"),
                Map.of("id", "b")));

        Map<String, Object> output = executor.execute(request, variables);

        assertEquals(Set.of("a", "b"), output.get("result"));
    }

    @Test
    void returnsEmptySetWhenVariableMissing() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.TRANSFORM)
                .input(Map.of("collectionVariable", "absent", "itemKey", "deptId"))
                .build();

        Map<String, Object> output = executor.execute(request, Map.of());

        assertEquals(Set.of(), output.get("result"));
    }

    @Test
    void rejectsMissingCollectionVariable() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.TRANSFORM)
                .input(Map.of("itemKey", "deptId"))
                .build();

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("collectionVariable"));
    }

    @Test
    void rejectsMissingItemKey() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.TRANSFORM)
                .input(Map.of("collectionVariable", "{{ employeeList }}"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
    }
}

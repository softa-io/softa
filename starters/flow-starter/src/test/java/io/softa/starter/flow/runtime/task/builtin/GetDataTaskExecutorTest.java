package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link GetDataTaskExecutor} — built-in DataTask querying via {@link ModelService}.
 */
class GetDataTaskExecutorTest {

    private ModelService<Serializable> modelService;
    private GetDataTaskExecutor executor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        modelService = mock(ModelService.class);
        executor = new GetDataTaskExecutor();
        // The executor uses @Autowired field injection; inject the mock by reflection.
        Field field = GetDataTaskExecutor.class.getDeclaredField("modelService");
        field.setAccessible(true);
        field.set(executor, modelService);
    }

    @Test
    void supportsGetRecordNodeType() {
        assertEquals(FlowNodeType.GET_RECORD, executor.getSupportedNodeType());
        assertEquals("GetData", executor.getExecutor());
    }

    @Test
    void countReturnsRowCount() {
        when(modelService.count(eq("Employee"), any())).thenReturn(7L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.GET_RECORD)
                .input(Map.of("modelName", "Employee", "getDataType", "Count"))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(7L, result.get("result"));
    }

    @Test
    void existReturnsTrueWhenCountPositive() {
        when(modelService.count(eq("Employee"), any())).thenReturn(3L);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.GET_RECORD)
                .input(Map.of("modelName", "Employee", "getDataType", "Exist"))
                .build();

        assertEquals(Boolean.TRUE, executor.execute(request, Map.of()).get("result"));
    }

    @Test
    void singleRowReturnsRowMap() {
        Map<String, Object> row = Map.of("name", "Alice", "deptId", 10L);
        when(modelService.searchOne(eq("Employee"), any())).thenReturn(Optional.of(row));

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.GET_RECORD)
                .input(Map.of(
                        "modelName", "Employee",
                        "getDataType", "SingleRow",
                        "fields", List.of("name", "deptId")))
                .build();

        assertEquals(row, executor.execute(request, Map.of()).get("result"));
    }

    @Test
    void oneFieldValueReturnsFirstFieldOfRow() {
        when(modelService.searchOne(eq("Employee"), any()))
                .thenReturn(Optional.of(Map.of("name", "Alice", "deptId", 10L)));

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.GET_RECORD)
                .input(Map.of(
                        "modelName", "Employee",
                        "getDataType", "OneFieldValue",
                        "fields", List.of("name")))
                .build();

        assertEquals("Alice", executor.execute(request, Map.of()).get("result"));
    }

    @Test
    void multiRowsReturnsRowList() {
        List<Map<String, Object>> rows = List.of(
                Map.of("name", "A"),
                Map.of("name", "B"));
        when(modelService.searchList(eq("Employee"), any())).thenReturn(rows);

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.GET_RECORD)
                .input(Map.of(
                        "modelName", "Employee",
                        "getDataType", "MultiRows",
                        "fields", List.of("name")))
                .build();

        assertEquals(rows, executor.execute(request, Map.of()).get("result"));
    }

    @Test
    void missingModelNameThrows() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.GET_RECORD)
                .input(Map.of("getDataType", "Count"))
                .build();

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("modelName"));
    }

    @Test
    void unsupportedGetDataTypeThrows() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.GET_RECORD)
                .input(Map.of("modelName", "Employee", "getDataType", "Bogus"))
                .build();

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("Bogus"));
    }

    @Test
    void singleRowReturnsNullWhenNoMatch() {
        when(modelService.searchOne(eq("Employee"), any())).thenReturn(Optional.empty());

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.GET_RECORD)
                .input(Map.of("modelName", "Employee", "getDataType", "SingleRow"))
                .build();

        assertNull(executor.execute(request, Map.of()).get("result"));
    }
}

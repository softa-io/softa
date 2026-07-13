package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CreateDataTaskExecutor} — builtin DataTask executor that creates a
 * single row via {@link ModelService#createOne}.
 */
class CreateDataTaskExecutorTest {

    private ModelService<Serializable> modelService;
    private CreateDataTaskExecutor executor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        modelService = mock(ModelService.class);
        executor = new CreateDataTaskExecutor();
        // CreateDataTaskExecutor uses @Autowired field injection; set it directly for the unit test.
        Field field = CreateDataTaskExecutor.class.getDeclaredField("modelService");
        field.setAccessible(true);
        field.set(executor, modelService);
    }

    @Test
    void reportsSupportedNodeTypeAndExecutorKey() {
        assertEquals(FlowNodeType.CREATE_RECORD, executor.getSupportedNodeType());
        assertEquals("CreateData", executor.getExecutor());
    }

    @Test
    void createsRowFromLiteralTemplateAndReturnsId() {
        when(modelService.createOne(eq("SysModel"), ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(42L);

        Map<String, Object> rowTemplate = new HashMap<>();
        rowTemplate.put("name", "Customer");
        rowTemplate.put("fieldType", "DateTime");

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CREATE_RECORD)
                .input(Map.of("modelName", "SysModel", "rowTemplate", rowTemplate))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals(42L, result.get("id"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> rowCaptor = ArgumentCaptor.forClass(Map.class);
        verify(modelService).createOne(eq("SysModel"), rowCaptor.capture());
        Map<String, Object> persisted = rowCaptor.getValue();
        // Literal (non-placeholder) values pass through resolveRowTemplate verbatim.
        assertEquals("Customer", persisted.get("name"));
        assertEquals("DateTime", persisted.get("fieldType"));
    }

    @Test
    void resolvesVariablePlaceholderInRowTemplate() {
        when(modelService.createOne(eq("SysModel"), ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn(7L);

        Map<String, Object> rowTemplate = new HashMap<>();
        rowTemplate.put("name", "{{ modelName }}");

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CREATE_RECORD)
                .input(Map.of("modelName", "SysModel", "rowTemplate", rowTemplate))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of("modelName", "ResolvedName"));

        assertEquals(7L, result.get("id"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> rowCaptor = ArgumentCaptor.forClass(Map.class);
        verify(modelService).createOne(eq("SysModel"), rowCaptor.capture());
        // The {{ modelName }} variable placeholder is resolved from the execution variables.
        assertEquals("ResolvedName", rowCaptor.getValue().get("name"));
    }

    @Test
    void rejectsMissingModelName() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CREATE_RECORD)
                .input(Map.of("rowTemplate", Map.of("name", "X")))
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
    }

    @Test
    void rejectsBlankModelName() {
        Map<String, Object> input = new HashMap<>();
        input.put("modelName", "   ");
        input.put("rowTemplate", Map.of("name", "X"));

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CREATE_RECORD)
                .input(input)
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
    }

    @Test
    void rejectsMissingRowTemplate() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CREATE_RECORD)
                .input(Map.of("modelName", "SysModel"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
    }

    @Test
    void rejectsEmptyRowTemplate() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CREATE_RECORD)
                .input(Map.of("modelName", "SysModel", "rowTemplate", Map.of()))
                .build();

        assertThrows(IllegalArgumentException.class, () -> executor.execute(request, Map.of()));
    }
}

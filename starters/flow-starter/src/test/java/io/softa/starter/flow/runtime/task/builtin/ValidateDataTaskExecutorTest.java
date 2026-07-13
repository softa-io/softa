package io.softa.starter.flow.runtime.task.builtin;

import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ValidateDataTaskExecutor} — boolean-expression validation
 * over execution variables, with interpolated failure messages.
 */
class ValidateDataTaskExecutorTest {

    private final ValidateDataTaskExecutor executor = new ValidateDataTaskExecutor();

    @Test
    void passingExpressionReturnsValid() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.VALIDATE_DATA)
                .input(Map.of(
                        "expression", "amount > 0 && amount <= budget",
                        "exceptionMsg", "Amount {{ amount }} exceeds budget {{ budget }}!"))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of("amount", 50, "budget", 100));

        assertEquals(Boolean.TRUE, result.get("valid"));
    }

    @Test
    void failingExpressionThrowsBusinessExceptionWithInterpolatedMessage() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.VALIDATE_DATA)
                .input(Map.of(
                        "expression", "amount <= budget",
                        "exceptionMsg", "Amount {{ amount }} exceeds budget {{ budget }}!"))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.execute(request, Map.of("amount", 150, "budget", 100)));

        assertEquals("Amount 150 exceeds budget 100!", ex.getMessage());
    }

    @Test
    void missingExpressionThrows() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.VALIDATE_DATA)
                .input(Map.of("exceptionMsg", "boom"))
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request, Map.of("amount", 1)));
        assertTrue(ex.getMessage().contains("input.expression"));
    }

    @Test
    void blankExpressionThrows() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.VALIDATE_DATA)
                .input(Map.of("expression", "   ", "exceptionMsg", "boom"))
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("input.expression"));
    }

    @Test
    void missingExceptionMsgThrows() {
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.VALIDATE_DATA)
                .input(Map.of("expression", "amount > 0"))
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(request, Map.of("amount", 1)));
        assertTrue(ex.getMessage().contains("input.exceptionMsg"));
    }
}

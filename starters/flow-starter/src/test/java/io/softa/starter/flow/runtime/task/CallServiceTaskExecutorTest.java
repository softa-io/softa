package io.softa.starter.flow.runtime.task;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.task.builtin.CallServiceTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CallServiceTaskExecutor} — default-safe allow-list.
 */
class CallServiceTaskExecutorTest {

    /** A stand-in domain bean the allow-list may permit. */
    static class OrderService {
        public String submit(String orderId) {
            return "submitted:" + orderId;
        }
    }

    @Test
    void constructorRejectsEmptyAllowList() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new CallServiceTaskExecutor(ctx, ""));
        assertTrue(ex.getMessage().contains("allow-list must not be empty"));
    }

    @Test
    void constructorRejectsBlankAllowList() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        assertThrows(IllegalStateException.class, () -> new CallServiceTaskExecutor(ctx, "   "));
    }

    @Test
    void constructorRejectsNullAllowList() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        assertThrows(IllegalStateException.class, () -> new CallServiceTaskExecutor(ctx, null));
    }

    @Test
    void invokesPermittedBean() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("orderService")).thenReturn(new OrderService());
        CallServiceTaskExecutor executor = new CallServiceTaskExecutor(ctx, "orderService");

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_SERVICE)
                .input(Map.of("beanName", "orderService", "methodName", "submit", "args", List.of("O-1")))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of());

        assertEquals("submitted:O-1", result.get("result"));
    }

    @Test
    void resolvesPlaceholderArgsFromVariables() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("orderService")).thenReturn(new OrderService());
        CallServiceTaskExecutor executor = new CallServiceTaskExecutor(ctx, "orderService");

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_SERVICE)
                .input(Map.of("beanName", "orderService", "methodName", "submit",
                        "args", List.of("{{ orderId }}")))
                .build();

        Map<String, Object> result = executor.execute(request, Map.of("orderId", "O-3"));

        assertEquals("submitted:O-3", result.get("result"));
    }

    @Test
    void rejectsBeanOutsideAllowList() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        CallServiceTaskExecutor executor = new CallServiceTaskExecutor(ctx, "orderService,quotationService");

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_SERVICE)
                .input(Map.of("beanName", "dataSource", "methodName", "getConnection"))
                .build();

        FlowRuntimeException ex =
                assertThrows(FlowRuntimeException.class, () -> executor.execute(request, Map.of()));
        assertTrue(ex.getMessage().contains("not permitted"));
    }

    @Test
    void allowListMatchesByPrefix() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("orderServiceImpl")).thenReturn(new OrderService());
        CallServiceTaskExecutor executor = new CallServiceTaskExecutor(ctx, "orderService");

        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(FlowNodeType.CALL_SERVICE)
                .input(Map.of("beanName", "orderServiceImpl", "methodName", "submit", "args", List.of("O-2")))
                .build();

        // "orderServiceImpl" starts with the permitted prefix "orderService" → invocation proceeds
        assertEquals("submitted:O-2", executor.execute(request, Map.of()).get("result"));
    }
}

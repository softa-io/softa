package io.softa.starter.flow.message;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.web.task.AsyncTaskFactory;
import io.softa.starter.flow.message.dto.FlowAsyncTaskMessage;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowWaitToken;
import io.softa.starter.flow.runtime.state.WaitType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the at-least-once dedup on the async-task consumer: a Pulsar redelivery whose flow has
 * already been resumed past the async node must NOT re-run the task body (duplicate side effect).
 */
class FlowAsyncTaskConsumerTest {

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static FlowAsyncTaskMessage message() {
        FlowAsyncTaskMessage m = new FlowAsyncTaskMessage();
        m.setInstanceId("inst-1");
        m.setNodeId("async-1");
        m.setAsyncTaskHandlerCode("generateInvoice");
        m.setAsyncTaskParams(Map.of());
        m.setContext(new Context());
        return m;
    }

    private static FlowExecutionState state(FlowExecutionStatus status, List<FlowWaitToken> tokens) {
        return FlowExecutionState.builder()
                .instanceId("inst-1")
                .status(status)
                .waitTokens(new ArrayList<>(tokens))
                .build();
    }

    @Test
    void firstDeliveryExecutesBodyAndResumes() throws Exception {
        AsyncTaskFactory<?> factory = mock(AsyncTaskFactory.class);
        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        FlowAsyncTaskConsumer consumer = new FlowAsyncTaskConsumer();
        inject(consumer, "asyncTaskFactory", factory);
        inject(consumer, "runtimeEngine", engine);

        // Flow still parked on the async node → live wait token present.
        when(engine.getInstance("inst-1")).thenReturn(Optional.of(state(FlowExecutionStatus.WAITING,
                List.of(FlowWaitToken.builder().nodeId("async-1").type(WaitType.ASYNC).build()))));

        consumer.onMessage(message());

        verify(factory).executeAsyncTask(eq("generateInvoice"), any());
        verify(engine).resumeAsyncTask(eq("inst-1"), eq("async-1"), any());
    }

    @Test
    void redeliveryAfterResumeSkipsBody() throws Exception {
        AsyncTaskFactory<?> factory = mock(AsyncTaskFactory.class);
        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        FlowAsyncTaskConsumer consumer = new FlowAsyncTaskConsumer();
        inject(consumer, "asyncTaskFactory", factory);
        inject(consumer, "runtimeEngine", engine);

        // Flow already resumed past the async node → no live wait token for it.
        when(engine.getInstance("inst-1"))
                .thenReturn(Optional.of(state(FlowExecutionStatus.RUNNING, List.of())));

        consumer.onMessage(message());

        verify(factory, never()).executeAsyncTask(any(), any());
        verify(engine, never()).resumeAsyncTask(any(), any(), any());
    }
}

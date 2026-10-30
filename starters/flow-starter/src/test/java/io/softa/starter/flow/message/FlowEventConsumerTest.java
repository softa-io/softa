package io.softa.starter.flow.message;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.message.dto.FlowEventMessage;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry.TriggerMatch;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A change reaches the flows twice: before the writer commits ({@code fireSyncOnly}, the sync flows)
 * and afterwards through the flow-event topic. The topic side must run only the flows with
 * {@code sync=false}, or every sync flow runs a second time.
 */
class FlowEventConsumerTest {

    private static CompiledFlowDefinition flow(String flowId, long bundleId, boolean sync) {
        return CompiledFlowDefinition.builder()
                .flowId(flowId)
                .flowName(flowId)
                .bundleId(bundleId)
                .scenario(FlowScenario.AUTOMATION)
                .sync(sync)
                .build();
    }

    private static FlowEventMessage changeEvent() {
        FlowEventMessage message = new FlowEventMessage();
        message.setTriggerType("EntityChange");
        message.setSourceModel("ExpenseClaim");
        message.setSourceRowId("7");
        message.setParameters(Map.of("amount", 120));
        return message;
    }

    @Test
    void runsOnlyTheFlowsThatDidNotRunBeforeTheCommit() {
        DefaultFlowTriggerRegistry registry = mock(DefaultFlowTriggerRegistry.class);
        when(registry.findMatches(any())).thenReturn(List.of(
                TriggerMatch.holding(flow("in-transaction", 1L, true)), TriggerMatch.holding(flow("after-commit", 2L, false))));
        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        when(engine.start(any())).thenReturn(new FlowExecutionState());

        FlowEventConsumer consumer = new FlowEventConsumer();
        ReflectionTestUtils.setField(consumer, "automationService", new FlowAutomationService(registry, engine));
        ReflectionTestUtils.setField(consumer, "runtimeEngine", engine);

        consumer.onMessage(changeEvent());

        ArgumentCaptor<FlowStartRequest> started = ArgumentCaptor.forClass(FlowStartRequest.class);
        verify(engine).start(started.capture());
        assertEquals(2L, started.getValue().getBundleId());
    }
}

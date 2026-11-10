package io.softa.starter.flow.message;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.changelog.message.dto.ChangeLogMessage;
import io.softa.framework.orm.enums.AccessType;
import io.softa.starter.flow.enums.FlowScenario;
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
 * and afterwards through the change-log topic. The topic side must run only the flows with
 * {@code sync=false}, or every sync flow runs a second time.
 */
class ChangeLogFlowConsumerTest {

    private static CompiledFlowDefinition flow(String flowId, long bundleId, boolean sync) {
        return CompiledFlowDefinition.builder()
                .flowId(flowId)
                .flowName(flowId)
                .bundleId(bundleId)
                .scenario(FlowScenario.AUTOMATION)
                .sync(sync)
                .build();
    }

    private static ChangeLogMessage changeLog() {
        ChangeLog changeLog = new ChangeLog();
        changeLog.setUuid("change-1");
        changeLog.setModel("ExpenseClaim");
        changeLog.setRowId("7");
        changeLog.setAccessType(AccessType.CREATE);
        changeLog.setDataAfterChange(Map.of("amount", 120));
        ChangeLogMessage message = new ChangeLogMessage();
        message.setChangeLogs(List.of(changeLog));
        message.setContext(new Context());
        return message;
    }

    @Test
    void runsOnlyTheFlowsThatDidNotRunBeforeTheCommit() {
        DefaultFlowTriggerRegistry registry = mock(DefaultFlowTriggerRegistry.class);
        when(registry.hasEntityChangeTrigger("ExpenseClaim")).thenReturn(true);
        when(registry.findMatches(any())).thenReturn(List.of(
                TriggerMatch.holding(flow("in-transaction", 1L, true)), TriggerMatch.holding(flow("after-commit", 2L, false))));
        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        when(engine.start(any())).thenReturn(new FlowExecutionState());

        ChangeLogFlowConsumer consumer = new ChangeLogFlowConsumer();
        ReflectionTestUtils.setField(consumer, "automationService", new FlowAutomationService(registry, engine));

        consumer.onMessage(changeLog());

        ArgumentCaptor<FlowStartRequest> started = ArgumentCaptor.forClass(FlowStartRequest.class);
        verify(engine).start(started.capture());
        assertEquals(2L, started.getValue().getBundleId());
    }
}

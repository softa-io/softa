package io.softa.starter.flow.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowCcReadRequest;
import io.softa.starter.flow.runtime.api.FlowCcRequest;
import io.softa.starter.flow.runtime.api.FlowDelegateRequest;
import io.softa.starter.flow.runtime.api.FlowRejectRequest;
import io.softa.starter.flow.runtime.api.FlowReturnRequest;
import io.softa.starter.flow.runtime.api.FlowTransferRequest;
import io.softa.starter.flow.runtime.api.FlowWithdrawRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An approval's participants read its state — the instance, and every action's answer — without the record's
 * values (zingkey-hcm#2402). The variables hold the record's fields as submitted and what later steps worked out,
 * past the field permissions the record's own read applies: a field a step hid could be read off the instance. The
 * engine's state keeps them.
 */
class FlowInstanceStateWithoutRecordValuesTest {

    private static final String APPROVER = "2001";

    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final FlowUserNames names = mock(FlowUserNames.class);
    private final FlowInstanceController controller = new FlowInstanceController(engine, null, null, null,
            mock(FlowInstanceAccessGuard.class), null, null, null, null, null, names, null);

    @Test
    void theInstanceIsReadWithoutTheRecordsValues() {
        FlowExecutionState state = withRecordValues();
        when(engine.getInstance("i1")).thenReturn(Optional.of(state));
        when(names.namesOf(anyList())).thenReturn(Map.of("1001", "Ann Lee"));

        FlowExecutionState read = as(APPROVER, () -> controller.getInstance("i1", false)).getData();

        assertWithoutRecordValues(read);
        assertThat(read.getInitiatorName()).isEqualTo("Ann Lee");
        // The engine's own state is left as it was.
        assertThat(state.getVariables()).containsEntry("startDate", "2026-11-03");
    }

    @Test
    void noApprovalActionAnswersWithThem() {
        when(engine.approve(any())).thenAnswer(invocation -> withRecordValues());
        when(engine.reject(any())).thenAnswer(invocation -> withRecordValues());
        when(engine.transfer(any())).thenAnswer(invocation -> withRecordValues());
        when(engine.delegate(any())).thenAnswer(invocation -> withRecordValues());
        when(engine.cc(any())).thenAnswer(invocation -> withRecordValues());
        when(engine.readCc(any())).thenAnswer(invocation -> withRecordValues());
        when(engine.returnApproval(any())).thenAnswer(invocation -> withRecordValues());
        when(engine.withdraw(any())).thenAnswer(invocation -> withRecordValues());

        List<Function<FlowInstanceController, ApiResponse<FlowExecutionState>>> actions = List.of(
                endpoint -> endpoint.approve(new FlowApproveRequest()),
                endpoint -> endpoint.reject(new FlowRejectRequest()),
                endpoint -> endpoint.transfer(new FlowTransferRequest()),
                endpoint -> endpoint.delegate(new FlowDelegateRequest()),
                endpoint -> endpoint.cc(new FlowCcRequest()),
                endpoint -> endpoint.readCc(new FlowCcReadRequest()),
                endpoint -> endpoint.returnApproval(new FlowReturnRequest()),
                endpoint -> endpoint.withdraw(new FlowWithdrawRequest()));

        for (Function<FlowInstanceController, ApiResponse<FlowExecutionState>> action : actions) {
            assertWithoutRecordValues(as(APPROVER, () -> action.apply(controller)).getData());
        }
    }

    /** A record's approval as the engine holds it: the record's fields among its variables. */
    private static FlowExecutionState withRecordValues() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("startDate", "2026-11-03");
        variables.put("totalAmount", 6000);
        return FlowExecutionState.builder()
                .instanceId("i1").modelName("ExpenseClaim").rowId("7").recordApproval(true)
                .status(FlowExecutionStatus.WAITING).initiatorId("1001").title("Taxi to client")
                .inputPayload(Map.of("totalAmount", 6000))
                .variables(variables)
                .returnData(Map.of("ok", true))
                .build();
    }

    private static void assertWithoutRecordValues(FlowExecutionState state) {
        assertThat(state.getVariables()).isNull();
        assertThat(state.getInputPayload()).isNull();
        assertThat(state.getReturnData()).isNull();
        // What shows the approval stays.
        assertThat(state.getInstanceId()).isEqualTo("i1");
        assertThat(state.getTitle()).isEqualTo("Taxi to client");
        assertThat(state.getStatus()).isEqualTo(FlowExecutionStatus.WAITING);
    }

    private static <T> T as(String userId, Supplier<T> call) {
        Context context = new Context();
        context.setUserId(Long.valueOf(userId));
        return ContextHolder.callWith(context, call::get);
    }
}

package io.softa.starter.flow.controller;

import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.runtime.api.FlowWithdrawRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.WithdrawPolicyGuard;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Withdraw on the approval page and in My Requests, on a record's approval that failed: its submitter takes the record
 * back as from the record's own page — a draft again, the failed instance kept. An approval waiting
 * on its approvers is withdrawn by the engine, as before.
 */
class FlowInstanceWithdrawFailedTest {

    private static final String SUBMITTER = "1001";

    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final FlowLaunchService launchService = mock(FlowLaunchService.class);
    private final WithdrawPolicyGuard guard = mock(WithdrawPolicyGuard.class);
    private final FlowInstanceController controller = new FlowInstanceController(engine, null, null, null,
            mock(FlowInstanceAccessGuard.class), launchService, null, guard, null, null, mock(FlowUserNames.class), null);

    @Test
    void aFailedApprovalIsTakenBackFromItsRecord() {
        FlowExecutionState failed = approval(FlowExecutionStatus.FAILED);
        when(engine.getInstance("i1")).thenReturn(Optional.of(failed));
        when(guard.takesBack(failed, SUBMITTER)).thenReturn(true);
        when(launchService.withdrawRecord("ExpenseClaim", "42", SUBMITTER, "will fix it")).thenReturn(failed);

        as(SUBMITTER, () -> controller.withdraw(request("will fix it")));

        verify(launchService).withdrawRecord("ExpenseClaim", "42", SUBMITTER, "will fix it");
        verify(engine, never()).withdraw(any());
    }

    @Test
    void anApprovalWaitingOnItsApproversIsWithdrawnByTheEngine() {
        FlowExecutionState waiting = approval(FlowExecutionStatus.WAITING);
        when(engine.getInstance("i1")).thenReturn(Optional.of(waiting));
        when(engine.withdraw(any())).thenReturn(waiting);

        as(SUBMITTER, () -> controller.withdraw(request(null)));

        verify(engine).withdraw(any());
        verify(launchService, never()).withdrawRecord(any(), any(), any(), any());
    }

    private static FlowExecutionState approval(FlowExecutionStatus status) {
        return FlowExecutionState.builder()
                .instanceId("i1").modelName("ExpenseClaim").rowId("42").recordApproval(true)
                .initiatorId(SUBMITTER).status(status)
                .build();
    }

    private static FlowWithdrawRequest request(String comment) {
        FlowWithdrawRequest request = new FlowWithdrawRequest();
        request.setInstanceId("i1");
        request.setComment(comment);
        return request;
    }

    private static <T> T as(String userId, Supplier<T> call) {
        Context context = new Context();
        context.setUserId(Long.valueOf(userId));
        return ContextHolder.callWith(context, call::get);
    }
}

package io.softa.starter.flow.runtime.engine;

import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A record's approval that failed waits on nobody, so its submitter takes the record back — whatever the flow's withdraw
 * policy, while the record still waits on that approval (zingkey-hcm#2385). The guard says so wherever Withdraw is
 * offered — the record's page, the approval page, My Requests — and only then.
 */
class WithdrawPolicyGuardTakeBackTest {

    private static final String SUBMITTER = "1001";

    private final FailedApprovalRecords failedApprovals = mock(FailedApprovalRecords.class);
    private final WithdrawPolicyGuard guard = new WithdrawPolicyGuard(new StubFlowBundleRegistry(),
            new ApprovalAuditReader(new NoopApprovalActionLedger()), failedApprovals);

    @Test
    void itsSubmitterTakesBackARecordWhoseApprovalFailed() {
        FlowExecutionState failed = failed(true);
        when(failedApprovals.standingOf(failed)).thenReturn(FailedApprovalRecords.Standing.WAITS);

        assertTrue(guard.takesBack(failed, SUBMITTER));
        assertTrue(guard.withdrawable(failed, SUBMITTER), "offered wherever Withdraw is");
        assertFalse(guard.withdrawable(failed, "2001"), "only by its submitter");
    }

    @Test
    void notOnceTheRecordMovedOn() {
        FlowExecutionState failed = failed(true);
        when(failedApprovals.standingOf(failed))
                .thenReturn(FailedApprovalRecords.Standing.MOVED_ON, FailedApprovalRecords.Standing.SUBMITTED_AGAIN);

        assertFalse(guard.withdrawable(failed, SUBMITTER), "taken back already");
        assertFalse(guard.withdrawable(failed, SUBMITTER), "submitted again: it waits on its new approval");
    }

    @Test
    void aFailedRunThatIsNoRecordsApprovalHasNothingToTakeBack() {
        assertFalse(guard.withdrawable(failed(false), SUBMITTER));
        verifyNoInteractions(failedApprovals);
    }

    private static FlowExecutionState failed(boolean recordApproval) {
        return FlowExecutionState.builder()
                .instanceId("failed-1").initiatorId(SUBMITTER).recordApproval(recordApproval)
                .modelName("ExpenseClaim").rowId("42").status(FlowExecutionStatus.FAILED)
                .build();
    }
}

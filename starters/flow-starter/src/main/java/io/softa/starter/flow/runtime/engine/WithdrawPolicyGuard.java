package io.softa.starter.flow.runtime.engine;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.WithdrawPolicy;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Whether an approval's initiator may withdraw it now, by the flow's {@link WithdrawPolicy}: the one rule the
 * withdraw action enforces and every client shows — the record's approval preview, the instance page, the
 * My Requests list — so a Withdraw button is offered exactly when the withdrawal would be accepted. A record's
 * approval that failed is withdrawn too, whatever the policy: its submitter takes the record back
 * ({@link #takesBack}).
 */
@Component
public class WithdrawPolicyGuard {

    /**
     * An approver acting: what ends a {@link WithdrawPolicy#BEFORE_FIRST_ACTION} window. An automatic
     * approval ({@code AUTO_APPROVE}) is not one, nor is a CC, a read, an urge or a comment. {@code ADD_SIGN}
     * is no longer recorded, but an approval still running may have it in its history from before.
     */
    private static final Set<ApprovalActionType> APPROVER_ACTIONS = EnumSet.of(ApprovalActionType.APPROVE,
            ApprovalActionType.REJECT, ApprovalActionType.RETURN, ApprovalActionType.TRANSFER,
            ApprovalActionType.DELEGATE, ApprovalActionType.ADD_SIGN);

    private final FlowBundleRegistry bundleRegistry;

    private final ApprovalAuditReader auditReader;

    private final FailedApprovalRecords failedApprovals;

    public WithdrawPolicyGuard(FlowBundleRegistry bundleRegistry, ApprovalAuditReader auditReader,
                               FailedApprovalRecords failedApprovals) {
        this.bundleRegistry = bundleRegistry;
        this.auditReader = auditReader;
        this.failedApprovals = failedApprovals;
    }

    /** The flow's policy; {@link WithdrawPolicy#NEVER} when unconfigured. */
    public static WithdrawPolicy policyOf(CompiledFlowDefinition definition) {
        return definition.getWithdrawPolicy() == null ? WithdrawPolicy.NEVER : definition.getWithdrawPolicy();
    }

    /**
     * Whether the policy still lets the approval be withdrawn. The approval's history is read only for
     * {@link WithdrawPolicy#BEFORE_FIRST_ACTION}.
     */
    public boolean policyPermits(CompiledFlowDefinition definition, FlowExecutionState state) {
        return switch (policyOf(definition)) {
            case NEVER -> false;
            case UNTIL_COMPLETED -> true;
            case BEFORE_FIRST_ACTION -> auditReader.fullHistory(state).stream()
                    .noneMatch(entry -> APPROVER_ACTIONS.contains(entry.getAction()));
        };
    }

    /**
     * Whether {@code requesterId} may withdraw the instance now: it is an approval waiting on its approvers,
     * they are its initiator, and the flow's policy permits it — or a record's approval that failed, which they
     * take the record back from ({@link #takesBack}).
     */
    public boolean withdrawable(FlowExecutionState state, String requesterId) {
        if (takesBack(state, requesterId)) {
            return true;
        }
        if (state == null || requesterId == null || !FlowExecutionStatus.WAITING.equals(state.getStatus())
                || !Objects.equals(requesterId, state.getInitiatorId()) || state.getBundleId() == null) {
            return false;
        }
        return bundleRegistry.getByBundleId(state.getBundleId())
                .filter(definition -> FlowScenario.APPROVAL.equals(definition.getScenario()))
                .map(definition -> policyPermits(definition, state))
                .orElse(false);
    }

    /**
     * Whether {@code requesterId} may take the record back from its approval that failed (zingkey-hcm#2385): they
     * submitted it, and the record still waits on that approval ({@link FailedApprovalRecords}). No policy limits it —
     * nobody is left to act on the approval. The record becomes a draft again, to submit again on the flow as it is
     * by then; the failed instance stays as it is, for flow monitoring.
     */
    public boolean takesBack(FlowExecutionState state, String requesterId) {
        return state != null && requesterId != null && FlowExecutionStatus.FAILED.equals(state.getStatus())
                && state.isApprovalOfRecord() && Objects.equals(requesterId, state.getInitiatorId())
                && failedApprovals.standingOf(state) == FailedApprovalRecords.Standing.WAITS;
    }
}

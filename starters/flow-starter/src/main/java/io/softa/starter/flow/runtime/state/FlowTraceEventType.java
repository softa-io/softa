package io.softa.starter.flow.runtime.state;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Trace event types emitted by the runtime engine.
 */
@Getter
@AllArgsConstructor
public enum FlowTraceEventType {
    ENTER_NODE("EnterNode"),
    COMPLETE_NODE("CompleteNode"),
    WAIT_APPROVAL("WaitApproval"),
    APPROVAL_RESUMED("ApprovalResumed"),
    APPROVAL_REJECTED("ApprovalRejected"),
    APPROVAL_TRANSFERRED("ApprovalTransferred"),
    APPROVAL_DELEGATED("ApprovalDelegated"),
    APPROVAL_ADD_SIGNED("ApprovalAddSigned"),
    APPROVAL_CCED("ApprovalCced"),
    APPROVAL_CC_READ("ApprovalCcRead"),
    APPROVAL_RETURNED("ApprovalReturned"),
    FLOW_RESUBMITTED("FlowResubmitted"),
    FLOW_WITHDRAWN("FlowWithdrawn"),
    FORK_PARALLEL("ForkParallel"),
    JOIN_PARALLEL("JoinParallel"),
    SUBFLOW_START("SubflowStart"),
    SUBFLOW_END("SubflowEnd"),
    LOOP_START("LoopStart"),
    LOOP_ITERATION("LoopIteration"),
    LOOP_END("LoopEnd"),
    FLOW_COMPLETED("FlowCompleted"),
    FLOW_FAILED("FlowFailed"),
    NODE_ERROR_RETRIED("NodeErrorRetried"),
    FLOW_URGED("FlowUrged"),
    ;

    @JsonValue
    private final String type;
}

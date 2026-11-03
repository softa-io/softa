package io.softa.starter.flow.runtime.state;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;

/**
 * Trace event types emitted by the runtime engine. Carries {@code @OptionSet} because
 * {@code FlowExecutionTrace.eventType} is an OPTION field — the trace viewer renders it
 * through the generic metadata surface.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum FlowTraceEventType {
    ENTER_NODE("EnterNode"),
    COMPLETE_NODE("CompleteNode"),
    WAIT_APPROVAL("WaitApproval"),
    APPROVAL_RESUMED("ApprovalResumed"),
    APPROVAL_REJECTED("ApprovalRejected"),
    APPROVAL_TRANSFERRED("ApprovalTransferred"),
    APPROVAL_DELEGATED("ApprovalDelegated"),
    APPROVAL_ADD_SIGNED("ApprovalAddSigned"),
    // Explicit labels: humanize flattens the CC acronym to "Cced" / "Cc Read".
    @OptionItem(label = "Approval CC'd")
    APPROVAL_CCED("ApprovalCced"),
    @OptionItem(label = "Approval CC Read")
    APPROVAL_CC_READ("ApprovalCcRead"),
    APPROVAL_RETURNED("ApprovalReturned"),
    FLOW_WITHDRAWN("FlowWithdrawn"),
    FLOW_TERMINATED("FlowTerminated"),
    FORK_PARALLEL("ForkParallel"),
    JOIN_PARALLEL("JoinParallel"),
    SUBFLOW_START("SubflowStart"),
    SUBFLOW_END("SubflowEnd"),
    LOOP_START("LoopStart"),
    LOOP_ITERATION("LoopIteration"),
    LOOP_END("LoopEnd"),
    FLOW_COMPLETED("FlowCompleted"),
    FLOW_FAILED("FlowFailed"),
    /** An administrator ran the steps after an approval again, after they had failed. */
    FLOW_RETRIED("FlowRetried"),
    NODE_ERROR_RETRIED("NodeErrorRetried"),
    FLOW_URGED("FlowUrged"),
    ;

    @JsonValue
    private final String type;
}

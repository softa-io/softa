package io.softa.starter.flow.runtime.engine;

import java.util.Map;
import java.util.Optional;

import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Runtime engine for executing compiled flow bundles.
 */
public interface FlowRuntimeEngine {

    FlowExecutionState start(FlowStartRequest request);

    /**
     * Run a stateless Validation / Compute flow transiently: the same traversal as
     * {@link #start}, but with zero flow footprint — no instance row, no trace rows,
     * no notifications. Business writes made by task nodes still join the caller's
     * transaction. Rejects Process-scenario bundles.
     */
    FlowExecutionState evaluate(FlowStartRequest request);

    FlowExecutionState approve(FlowApproveRequest request);

    FlowExecutionState reject(FlowRejectRequest request);

    FlowExecutionState transfer(FlowTransferRequest request);

    FlowExecutionState delegate(FlowDelegateRequest request);

    FlowExecutionState addSignBefore(FlowAddSignBeforeRequest request);

    FlowExecutionState addSignAfter(FlowAddSignAfterRequest request);

    FlowExecutionState cc(FlowCcRequest request);

    FlowExecutionState batchCc(FlowBatchCcRequest request);

    FlowExecutionState readCc(FlowCcReadRequest request);

    FlowExecutionState returnApproval(FlowReturnRequest request);

    FlowExecutionState resubmit(FlowResubmitRequest request);

    FlowExecutionState withdraw(FlowWithdrawRequest request);

    FlowExecutionState urge(FlowUrgeRequest request);

    FlowExecutionState addComment(FlowCommentRequest request);

    /**
     * Current state of an instance, without the trace history (loads stay O(1) in
     * history length). Use {@link #getInstanceWithTrace} to render the event list.
     */
    Optional<FlowExecutionState> getInstance(String instanceId);

    /**
     * {@link #getInstance} plus the full hydrated trace — for view paths only.
     */
    default Optional<FlowExecutionState> getInstanceWithTrace(String instanceId) {
        return getInstance(instanceId);
    }

    /**
     * Resume a flow instance that is suspended waiting for an async-task callback.
     *
     * @param instanceId      the suspended instance id
     * @param nodeId          the async-task node that triggered the suspension
     * @param callbackOutputs outputs returned by the async task handler (may be empty)
     */
    FlowExecutionState resumeAsyncTask(String instanceId, String nodeId, Map<String, Object> callbackOutputs);

    /**
     * Resume a flow instance that is suspended waiting for a timer to fire.
     *
     * @param instanceId the suspended instance id
     * @param nodeId     the timer node that triggered the suspension
     */
    FlowExecutionState resumeTimer(String instanceId, String nodeId);
}



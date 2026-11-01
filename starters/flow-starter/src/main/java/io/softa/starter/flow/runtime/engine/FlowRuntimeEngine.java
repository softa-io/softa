package io.softa.starter.flow.runtime.engine;

import java.util.Map;
import java.util.Optional;

import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Runtime engine for executing compiled flow bundles.
 */
public interface FlowRuntimeEngine {

    /**
     * Start an instance of the requested bundle without asking how its flow is meant to start: for the
     * trigger dispatch, which matched the flow's trigger to the event, and for debug runs of a draft.
     * Callers starting a flow on someone's request use {@link #startAutomation}.
     */
    FlowExecutionState start(FlowStartRequest request);

    /**
     * Start a flow explicitly, by its id — the start an {@code Api} trigger declares. Only an
     * Automation flow whose trigger is {@code Api} starts this way; any other flow is refused, since
     * its trigger names another way in (record submission, an event, a parent flow).
     */
    FlowExecutionState startAutomation(FlowStartRequest request);

    /**
     * {@link #start}, as the approval of the request's record: the instance's result is written
     * back to the record, and the record can have only one such instance in progress. Only record
     * submission ({@code FlowLaunchService.submitRecord}) starts one; a debug run of the same flow
     * runs on the record without being its approval. A start that fails throws without keeping a
     * failed instance or telling anyone: the submission fails with it, and its transaction rolls back.
     */
    FlowExecutionState startRecordApproval(FlowStartRequest request);

    /**
     * Run a stateless Validation / Compute flow transiently: the same traversal as
     * {@link #start}, but with zero flow footprint — no instance row, no trace rows,
     * no notifications. Business writes made by task nodes still join the caller's
     * transaction. Rejects Approval and Automation bundles.
     */
    FlowExecutionState evaluate(FlowStartRequest request);

    FlowExecutionState approve(FlowApproveRequest request);

    FlowExecutionState reject(FlowRejectRequest request);

    FlowExecutionState transfer(FlowTransferRequest request);

    FlowExecutionState delegate(FlowDelegateRequest request);

    FlowExecutionState addSignBefore(FlowAddSignBeforeRequest request);

    FlowExecutionState addSignAfter(FlowAddSignAfterRequest request);

    FlowExecutionState cc(FlowCcRequest request);

    FlowExecutionState readCc(FlowCcReadRequest request);

    FlowExecutionState returnApproval(FlowReturnRequest request);

    FlowExecutionState withdraw(FlowWithdrawRequest request);

    FlowExecutionState urge(FlowUrgeRequest request);

    /**
     * Urges an approval as an administrator, who need not be its initiator — authorized by the caller
     * beforehand (the monitoring console's permission and data scope). The same interval applies as to the
     * initiator's urges.
     */
    FlowExecutionState urgeAsAdministrator(FlowUrgeRequest request);

    FlowExecutionState addComment(FlowCommentRequest request);

    /**
     * Ends an instance that runs, waits or failed, as an administrator — authorized by the caller beforehand
     * (the monitoring console's permission and data scope). The instance is cancelled, its record with it.
     */
    FlowExecutionState terminate(FlowTerminateRequest request);

    /**
     * Moves an approver's pending task to another approver, as an administrator — authorized by the caller
     * beforehand. Recorded as the approver's transfer with the administrator as its operator.
     */
    FlowExecutionState reassign(FlowReassignRequest request);

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



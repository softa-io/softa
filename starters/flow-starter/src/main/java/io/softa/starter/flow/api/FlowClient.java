package io.softa.starter.flow.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Single in-process port (host-facing facade) for a monolith that embeds the flow engine.
 * It mirrors {@code FlowRuntimeEngine}'s lifecycle/action methods plus the host-relevant
 * query and form-permission operations otherwise spread across the engine's controllers.
 * <p>
 * Every method exchanges only SDK DTOs. {@code actorId} / {@code initiatorId} / {@code tenantId}
 * are NEVER call parameters — they are resolved server-side from the context.
 * <p>
 * This is NOT a remote contract: in a microservice topology, actions go to the engine's
 * {@code FlowRuntimeController} REST API and flow-data queries go through the framework model
 * RPC — a linking application neither implements nor proxies {@code FlowClient}.
 */
public interface FlowClient {

    // lifecycle / launch
    FlowExecutionState start(FlowStartRequest request);

    FlowExecutionState publishAndStart(PublishAndStartRequest request);

    /**
     * Current state of an instance, viewer-checked: the resolved current user must be the
     * initiator or a participant — the same authorization the REST detail endpoints apply.
     * Trace history is not hydrated.
     */
    Optional<FlowExecutionState> getInstance(String instanceId);

    List<FlowInstanceView> myInstances(MineQuery query);

    // approval actions (1:1 with FlowRuntimeEngine)
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

    // form / field-permission
    Map<String, FormFieldPermission> getFormPermissions(String instanceId, String nodeId);

    // async / timer resume (engine-internal callers; exposed for custom TaskExecutors)
    FlowExecutionState resumeAsyncTask(String instanceId, String nodeId, Map<String, Object> callbackOutputs);

    FlowExecutionState resumeTimer(String instanceId, String nodeId);

    // inbox / task / record queries
    FlowInboxView inbox(InboxQuery query);

    List<FlowApprovalTaskView> pendingTasks(TaskQuery query);

    List<FlowApprovalTaskView> completedTasks(TaskQuery query);

    List<FlowApprovalTaskView> ccTasks(CcTaskQuery query);

    List<FlowApprovalTaskView> tasksByInstance(String instanceId);

    List<FlowApprovalRecordView> recordsByInstance(String instanceId);

    List<FlowApprovalRecordView> approvalHistory(TaskQuery query);

    List<FlowSentCcView> sentCcHistory(CcTaskQuery query);

    // palette (read-only descriptor list; live-bean enumeration stays engine-side)
    List<FlowNodeDescriptor> nodeDescriptors(FlowScenario scenario);
}

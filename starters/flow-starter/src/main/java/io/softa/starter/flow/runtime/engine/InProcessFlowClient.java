package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.starter.flow.api.*;
import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.dto.*;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.descriptor.FlowNodeDescriptorRegistry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.*;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * The single {@link FlowClient} implementation — a thin in-process adapter that resolves
 * {@code actorId} / {@code initiatorId} with {@code FlowActors.currentUserId()}, as the REST
 * controllers do, and makes the same engine and service calls as {@code FlowInstanceController}.
 * No new logic, no serialization, no signing — the monolith pays nothing.
 * {@code FlowAutoConfiguration} registers it only when the host provides no {@link FlowClient}.
 *
 * <p>The current user's own lists — instances, inbox, tasks, history — carry {@link SkipPermissionCheck}
 * like their REST counterparts: pinned to the caller, they need no grant on the flow models.
 */
public class InProcessFlowClient implements FlowClient {

    private final FlowRuntimeEngine engine;
    private final FlowLaunchService launch;
    private final FormPermissionService forms;
    private final FlowApprovalTaskQueryService taskQuery;
    private final FlowApprovalRecordQueryService recordQuery;
    private final FlowInboxService inbox;
    private final FlowInstanceService instances;
    private final FlowNodeDescriptorRegistry descriptors;
    private final FlowInstanceAccessGuard accessGuard;

    public InProcessFlowClient(FlowRuntimeEngine engine,
                               FlowLaunchService launch,
                               FormPermissionService forms,
                               FlowApprovalTaskQueryService taskQuery,
                               FlowApprovalRecordQueryService recordQuery,
                               FlowInboxService inbox,
                               FlowInstanceService instances,
                               FlowNodeDescriptorRegistry descriptors,
                               FlowInstanceAccessGuard accessGuard) {
        this.engine = engine;
        this.launch = launch;
        this.forms = forms;
        this.taskQuery = taskQuery;
        this.recordQuery = recordQuery;
        this.inbox = inbox;
        this.instances = instances;
        this.descriptors = descriptors;
        this.accessGuard = accessGuard;
    }

    // ── lifecycle / launch ──────────────────────────────────────────────────
    @Override
    public FlowExecutionState start(FlowStartRequest r) {
        r.setInitiatorId(currentUserId());
        return engine.startAutomation(r);
    }

    @Override
    public FlowExecutionState publishAndStart(PublishAndStartRequest r) {
        r.setInitiatorId(currentUserId());
        return launch.publishAndStart(r).getState();
    }

    @Override
    public FlowExecutionState submitRecord(String modelName, String rowId) {
        return launch.submitRecord(modelName, rowId, currentUserId());
    }

    @Override
    public FlowApprovalPreview previewRecord(String modelName, String rowId) {
        return launch.previewRecord(modelName, rowId, currentUserId());
    }

    @Override
    public FlowExecutionState withdrawRecord(String modelName, String rowId, String comment) {
        return launch.withdrawRecord(modelName, rowId, currentUserId(), comment);
    }

    @Override
    public void cancelRecord(String modelName, String rowId) {
        launch.cancelRecord(modelName, rowId, currentUserId());
    }

    @Override
    public FlowRecordApprovalsView recordApprovals(String modelName, String rowId) {
        String requesterId = currentUserId();
        return instances.recordApprovals(modelName, rowId, history -> {
            accessGuard.requireRecordViewer(modelName, rowId, history, requesterId);
            accessGuard.markViewable(modelName, rowId, history, requesterId);
        });
    }

    @Override
    public Optional<FlowExecutionState> getInstance(String instanceId) {
        // Same viewer authorization as the REST detail endpoints — this port's queries are
        // current-user-scoped exactly like its actions.
        return engine.getInstance(instanceId)
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, currentUserId());
                    return state;
                });
    }

    @Override
    @SkipPermissionCheck
    public List<FlowInstanceView> myInstances(MineQuery q) {
        // The search behind POST /flow/instances/search; the port returns its first (newest) page.
        FlowInstanceSearchRequest request = FlowInstanceSearchRequest.of(
                q == null ? null : q.flowId(), q == null ? null : q.status());
        return instances.searchSummaries(request, currentUserId()).getRows().stream()
                .map(InProcessFlowClient::toView)
                .toList();
    }

    // ── approval actions ────────────────────────────────────────────────────
    @Override
    public FlowExecutionState approve(FlowApproveRequest r) {
        r.setActorId(currentUserId());
        return engine.approve(r);
    }

    @Override
    public FlowExecutionState reject(FlowRejectRequest r) {
        r.setActorId(currentUserId());
        return engine.reject(r);
    }

    @Override
    public FlowExecutionState transfer(FlowTransferRequest r) {
        r.setActorId(currentUserId());
        return engine.transfer(r);
    }

    @Override
    public FlowExecutionState delegate(FlowDelegateRequest r) {
        r.setActorId(currentUserId());
        return engine.delegate(r);
    }

    @Override
    public FlowExecutionState addSignBefore(FlowAddSignBeforeRequest r) {
        r.setActorId(currentUserId());
        return engine.addSignBefore(r);
    }

    @Override
    public FlowExecutionState addSignAfter(FlowAddSignAfterRequest r) {
        r.setActorId(currentUserId());
        return engine.addSignAfter(r);
    }

    @Override
    public FlowExecutionState cc(FlowCcRequest r) {
        r.setActorId(currentUserId());
        return engine.cc(r);
    }

    @Override
    public FlowExecutionState readCc(FlowCcReadRequest r) {
        r.setActorId(currentUserId());
        return engine.readCc(r);
    }

    @Override
    public FlowExecutionState returnApproval(FlowReturnRequest r) {
        r.setActorId(currentUserId());
        return engine.returnApproval(r);
    }

    @Override
    public FlowExecutionState withdraw(FlowWithdrawRequest r) {
        r.setActorId(currentUserId());
        return engine.withdraw(r);
    }

    @Override
    public FlowExecutionState urge(FlowUrgeRequest r) {
        r.setActorId(currentUserId());
        return engine.urge(r);
    }

    @Override
    public FlowExecutionState addComment(FlowCommentRequest r) {
        r.setActorId(currentUserId());
        return engine.addComment(r);
    }

    // ── form / field-permission ─────────────────────────────────────────────
    @Override
    public Map<String, FormFieldPermission> getFormPermissions(String instanceId, String nodeId) {
        // Viewer-checked like GET /flow/instances/{instanceId}/nodes/{nodeId}/formPermissions.
        return getInstance(instanceId)
                .map(state -> forms.getFieldPermissions(instanceId, nodeId))
                .orElse(Map.of());
    }

    // ── async / timer resume ────────────────────────────────────────────────
    @Override
    public FlowExecutionState resumeAsyncTask(String instanceId, String nodeId, Map<String, Object> callbackOutputs) {
        return engine.resumeAsyncTask(instanceId, nodeId, callbackOutputs);
    }

    @Override
    public FlowExecutionState resumeTimer(String instanceId, String nodeId) {
        return engine.resumeTimer(instanceId, nodeId);
    }

    // ── inbox / task / record queries ───────────────────────────────────────
    @Override
    @SkipPermissionCheck
    public FlowInboxView inbox(InboxQuery q) {
        return inbox.getInbox(currentUserId(), q.flowId(), q.instanceId(), q.nodeId(), q.includeCompletedApprovals());
    }

    @Override
    @SkipPermissionCheck
    public List<FlowApprovalTaskView> pendingTasks(TaskQuery q) {
        return taskQuery.getPendingTasks(currentUserId(), q.flowId(), q.instanceId(), q.nodeId());
    }

    @Override
    @SkipPermissionCheck
    public List<FlowApprovalTaskView> completedTasks(TaskQuery q) {
        return taskQuery.getCompletedTasks(currentUserId(), q.flowId(), q.instanceId(), q.nodeId());
    }

    @Override
    @SkipPermissionCheck
    public List<FlowApprovalTaskView> ccTasks(CcTaskQuery q) {
        return taskQuery.getCcTasks(currentUserId(), q.read(), q.flowId(), q.instanceId(), q.nodeId());
    }

    @Override
    public List<FlowApprovalTaskView> tasksByInstance(String instanceId) {
        return taskQuery.getTasksByInstanceId(instanceId, currentUserId());
    }

    @Override
    public List<FlowApprovalRecordView> recordsByInstance(String instanceId) {
        return recordQuery.getByInstanceId(instanceId, currentUserId());
    }

    @Override
    @SkipPermissionCheck
    public List<FlowApprovalRecordView> approvalHistory(TaskQuery q) {
        // History is paged now; the in-process convenience returns the newest page.
        return recordQuery.getHistory(currentUserId(), q.flowId(), q.instanceId(), q.nodeId(), null, null).getRows();
    }

    @Override
    @SkipPermissionCheck
    public List<FlowSentCcView> sentCcHistory(CcTaskQuery q) {
        return recordQuery.getSentCcHistory(currentUserId(), q.read(), q.flowId(), q.instanceId(), q.nodeId());
    }

    // ── palette ─────────────────────────────────────────────────────────────
    @Override
    public List<FlowNodeDescriptor> nodeDescriptors(FlowScenario scenario) {
        return scenario == null
                ? new ArrayList<>(descriptors.list())
                : descriptors.listByScenario(scenario);
    }

    private static FlowInstanceView toView(FlowInstance i) {
        return new FlowInstanceView(i.getInstanceId(), i.getFlowId(), i.getFlowName(), i.getTitle(),
                i.getStatus(), i.getCreatedTime());
    }
}

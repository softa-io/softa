package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.api.CcTaskQuery;
import io.softa.starter.flow.api.FlowClient;
import io.softa.starter.flow.api.FlowInstanceView;
import io.softa.starter.flow.api.InboxQuery;
import io.softa.starter.flow.api.MineQuery;
import io.softa.starter.flow.api.TaskQuery;
import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.descriptor.FlowNodeDescriptorRegistry;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowApprovalRecordQueryService;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.FlowInboxService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

/**
 * The single {@link FlowClient} implementation — a thin in-process adapter that resolves
 * {@code actorId} / {@code initiatorId} from {@link ContextHolder} (exactly as
 * {@code FlowRuntimeController.currentUserId()} does) and delegates to the existing engine
 * beans. No new logic, no serialization, no signing — the monolith pays nothing.
 * Default-provided via {@link ConditionalOnMissingBean} so a host may override.
 */
@Component
@ConditionalOnMissingBean(FlowClient.class)
class InProcessFlowClient implements FlowClient {

    private final FlowRuntimeEngine engine;
    private final FlowLaunchService launch;
    private final FormPermissionService forms;
    private final FlowApprovalTaskQueryService taskQuery;
    private final FlowApprovalRecordQueryService recordQuery;
    private final FlowInboxService inbox;
    private final FlowInstanceService instances;
    private final FlowNodeDescriptorRegistry descriptors;
    private final FlowInstanceAccessGuard accessGuard;

    InProcessFlowClient(FlowRuntimeEngine engine,
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

    /** Server-resolved current actor, mirroring {@code FlowRuntimeController.currentUserId()}. */
    private static String me() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            throw new FlowAuthorizationException("Authentication is required");
        }
        return userId.toString();
    }

    // ── lifecycle / launch ──────────────────────────────────────────────────
    @Override
    public FlowExecutionState start(FlowStartRequest r) {
        r.setInitiatorId(me());
        return engine.start(r);
    }

    @Override
    public FlowExecutionState publishAndStart(PublishAndStartRequest r) {
        r.setInitiatorId(me());
        return launch.publishAndStart(r).getState();
    }

    @Override
    public Optional<FlowExecutionState> getInstance(String instanceId) {
        // Same viewer authorization as the REST detail endpoints — this port's queries are
        // current-user-scoped exactly like its actions.
        return engine.getInstance(instanceId)
                .map(state -> {
                    accessGuard.requireInstanceViewer(state, me());
                    return state;
                });
    }

    @Override
    public List<FlowInstanceView> myInstances(MineQuery q) {
        return instances.findByInitiatorId(me()).stream()
                .filter(i -> q == null || q.flowCode() == null || q.flowCode().equals(i.getFlowCode()))
                .filter(i -> q == null || q.status() == null || q.status() == i.getStatus())
                .map(InProcessFlowClient::toView)
                .toList();
    }

    // ── approval actions ────────────────────────────────────────────────────
    @Override
    public FlowExecutionState approve(FlowApproveRequest r) {
        r.setActorId(me());
        return engine.approve(r);
    }

    @Override
    public FlowExecutionState reject(FlowRejectRequest r) {
        r.setActorId(me());
        return engine.reject(r);
    }

    @Override
    public FlowExecutionState transfer(FlowTransferRequest r) {
        r.setActorId(me());
        return engine.transfer(r);
    }

    @Override
    public FlowExecutionState delegate(FlowDelegateRequest r) {
        r.setActorId(me());
        return engine.delegate(r);
    }

    @Override
    public FlowExecutionState addSignBefore(FlowAddSignBeforeRequest r) {
        r.setActorId(me());
        return engine.addSignBefore(r);
    }

    @Override
    public FlowExecutionState addSignAfter(FlowAddSignAfterRequest r) {
        r.setActorId(me());
        return engine.addSignAfter(r);
    }

    @Override
    public FlowExecutionState cc(FlowCcRequest r) {
        r.setActorId(me());
        return engine.cc(r);
    }

    @Override
    public FlowExecutionState batchCc(FlowBatchCcRequest r) {
        r.setActorId(me());
        return engine.batchCc(r);
    }

    @Override
    public FlowExecutionState readCc(FlowCcReadRequest r) {
        r.setActorId(me());
        return engine.readCc(r);
    }

    @Override
    public FlowExecutionState returnApproval(FlowReturnRequest r) {
        r.setActorId(me());
        return engine.returnApproval(r);
    }

    @Override
    public FlowExecutionState resubmit(FlowResubmitRequest r) {
        r.setActorId(me());
        return engine.resubmit(r);
    }

    @Override
    public FlowExecutionState withdraw(FlowWithdrawRequest r) {
        r.setActorId(me());
        return engine.withdraw(r);
    }

    @Override
    public FlowExecutionState urge(FlowUrgeRequest r) {
        r.setActorId(me());
        return engine.urge(r);
    }

    @Override
    public FlowExecutionState addComment(FlowCommentRequest r) {
        r.setActorId(me());
        return engine.addComment(r);
    }

    // ── form / field-permission ─────────────────────────────────────────────
    @Override
    public Map<String, FormFieldPermission> getFormPermissions(String instanceId, String nodeId) {
        return forms.getFieldPermissions(instanceId, nodeId);
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
    public FlowInboxView inbox(InboxQuery q) {
        return inbox.getInbox(me(), q.flowCode(), q.instanceId(), q.nodeId(), q.includeCompletedApprovals());
    }

    @Override
    public List<FlowApprovalTaskView> pendingTasks(TaskQuery q) {
        return taskQuery.getPendingTasks(me(), q.flowCode(), q.instanceId(), q.nodeId());
    }

    @Override
    public List<FlowApprovalTaskView> completedTasks(TaskQuery q) {
        return taskQuery.getCompletedTasks(me(), q.flowCode(), q.instanceId(), q.nodeId());
    }

    @Override
    public List<FlowApprovalTaskView> ccTasks(CcTaskQuery q) {
        return taskQuery.getCcTasks(me(), q.read(), q.flowCode(), q.instanceId(), q.nodeId());
    }

    @Override
    public List<FlowApprovalTaskView> tasksByInstance(String instanceId) {
        return taskQuery.getTasksByInstanceId(instanceId, me());
    }

    @Override
    public List<FlowApprovalRecordView> recordsByInstance(String instanceId) {
        return recordQuery.getByInstanceId(instanceId, me());
    }

    @Override
    public List<FlowApprovalRecordView> approvalHistory(TaskQuery q) {
        // History is paged now; the in-process convenience returns the newest page.
        return recordQuery.getHistory(me(), q.flowCode(), q.instanceId(), q.nodeId(), null, null).getRows();
    }

    @Override
    public List<FlowSentCcView> sentCcHistory(CcTaskQuery q) {
        return recordQuery.getSentCcHistory(me(), q.read(), q.flowCode(), q.instanceId(), q.nodeId());
    }

    // ── palette ─────────────────────────────────────────────────────────────
    @Override
    public List<FlowNodeDescriptor> nodeDescriptors(FlowScenario scenario) {
        return scenario == null
                ? new ArrayList<>(descriptors.list())
                : descriptors.listByScenario(scenario);
    }

    private static FlowInstanceView toView(FlowInstance i) {
        return new FlowInstanceView(i.getInstanceId(), i.getFlowCode(), i.getTitle(),
                i.getStatus(), i.getResubmissionCount(), i.getCreatedTime());
    }
}

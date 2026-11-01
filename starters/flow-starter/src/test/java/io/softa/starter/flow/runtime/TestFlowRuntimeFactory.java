package io.softa.starter.flow.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mockito.Mockito;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.runtime.action.*;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.impl.DefaultApprovalNotificationService;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

/**
 * Test helper to build a fully wired {@link FlowRuntimeFacade} without Spring context.
 */
public final class TestFlowRuntimeFactory {

    private TestFlowRuntimeFactory() {
    }

    public static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, state -> {}, state -> {});
    }

    public static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, taskSyncListener, state -> {});
    }

    public static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener,
                                    FlowStateChangeListener recordSyncListener) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, taskSyncListener,
                recordSyncListener, Mockito.mock(ModelService.class));
    }

    /**
     * As above, with the model service an approval's form edits are written to the record through — and the
     * access guard reads an instance's tasks through, when a comment asks who takes part in it.
     */
    public static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener,
                                    FlowStateChangeListener recordSyncListener,
                                    ModelService<?> recordModelService) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, taskSyncListener,
                recordSyncListener, recordModelService, new DefaultApprovalNotificationService());
    }

    /**
     * With the engine's task projection kept as its rows would be — a task, once given, stays a row, closed or not —
     * in {@code taskHolders}: the holders of each instance's tasks, by instance id. The access guard counts them
     * ({@link #taskRows}) when a comment asks who takes part in an instance.
     */
    public static FlowRuntimeFacade createWithTasks(FlowBundleRegistry bundleRegistry,
                                             FlowInstanceStore instanceStore,
                                             DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                             ApprovalNodeExecutionHandler approvalHandler,
                                             Map<String, Set<String>> taskHolders) {
        FlowApprovalTaskProjector projector = new FlowApprovalTaskProjector(
                new ApprovalAuditReader(new NoopApprovalActionLedger()));
        FlowStateChangeListener taskSync = state -> projector.project(state).forEach(task -> taskHolders
                .computeIfAbsent(task.getInstanceId(), instanceId -> new LinkedHashSet<>()).add(task.getActorId()));
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, taskSync, state -> {},
                taskRows(taskHolders));
    }

    /**
     * A model service holding the {@code FlowApprovalTask} rows of {@code taskHolders} as the access guard counts
     * them: a user's tasks on an instance, whatever their type and status.
     */
    public static ModelService<?> taskRows(Map<String, Set<String>> taskHolders) {
        ModelService<?> models = Mockito.mock(ModelService.class);
        Mockito.when(models.count(Mockito.eq(FlowApprovalTask.class.getSimpleName()), Mockito.any(Filters.class)))
                .thenAnswer(invocation -> taskHolders.entrySet().stream()
                        .flatMap(holders -> holders.getValue().stream().map(actorId -> new Filters()
                                .in(FlowApprovalTask::getInstanceId, List.of(holders.getKey()))
                                .eq(FlowApprovalTask::getActorId, actorId)))
                        .filter(filters -> filters.equals(invocation.getArgument(1)))
                        .count());
        return models;
    }

    /** With the notification service the engine reports to, for tests that watch what it announces. */
    public static FlowRuntimeFacade createWithNotifications(FlowBundleRegistry bundleRegistry,
                                                     FlowInstanceStore instanceStore,
                                                     DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                                     ApprovalNodeExecutionHandler approvalHandler,
                                                     ApprovalNotificationService notificationService) {
        return create(bundleRegistry, instanceStore, nodeHandlerRegistry, approvalHandler, state -> {}, state -> {},
                Mockito.mock(ModelService.class), notificationService);
    }

    private static FlowRuntimeFacade create(FlowBundleRegistry bundleRegistry,
                                    FlowInstanceStore instanceStore,
                                    DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                    ApprovalNodeExecutionHandler approvalHandler,
                                    FlowStateChangeListener taskSyncListener,
                                    FlowStateChangeListener recordSyncListener,
                                    ModelService<?> recordModelService,
                                    ApprovalNotificationService notificationService) {
        FlowProjectionPublisher projectionPublisher =
                new FlowProjectionPublisher(List.of(taskSyncListener, recordSyncListener));

        ApprovalAuditReader auditReader = new ApprovalAuditReader(new NoopApprovalActionLedger());
        FlowAuditService auditService = new FlowAuditService(auditReader);
        ApproverResolutionService approverResolutionService = new ApproverResolutionService();
        PendingApprovalFactory pendingApprovalFactory = new PendingApprovalFactory(approverResolutionService, auditReader);
        ApprovalLifecycleService lifecycleService = new ApprovalLifecycleService(pendingApprovalFactory);
        ApprovalActorValidator actorValidator = new ApprovalActorValidator(lifecycleService);
        FlowActionContextService contextService = new FlowActionContextService(instanceStore, bundleRegistry, projectionPublisher);

        FlowGatewayRouter gatewayRouter = new FlowGatewayRouter();
        NodeRetryExecutor retryExecutor = new NodeRetryExecutor(auditService);
        ApproverDedupService approverDedupService = new ApproverDedupService(lifecycleService, auditService, auditReader);
        FlowActionReplayGuard replayGuard = new FlowActionReplayGuard(auditReader);
        FlowExecutionOrchestrator orchestrator = new FlowExecutionOrchestrator(
                bundleRegistry, nodeHandlerRegistry, contextService, auditService, pendingApprovalFactory, notificationService,
                gatewayRouter, retryExecutor, approverDedupService);

        AutoCcService autoCcService = new AutoCcService(contextService, auditService, auditReader, notificationService,
                approverResolutionService);
        FlowInstanceAccessGuard accessGuard = new FlowInstanceAccessGuard(Mockito.mock(FlowInstanceService.class),
                auditReader, recordModelService);

        return new FlowRuntimeFacade(contextService, orchestrator, instanceStore, autoCcService,
                new ApproveActionHandler(contextService, actorValidator, lifecycleService, auditService, orchestrator, notificationService,
                        new ApprovalFormWriteService(recordModelService, ModelApprovalHandlerRegistry.of(List.of())), replayGuard),
                new RejectActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService, replayGuard),
                new TransferActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService),
                new DelegateActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService),
                new AddSignBeforeActionHandler(contextService, actorValidator, lifecycleService, auditService),
                new AddSignAfterActionHandler(contextService, actorValidator, lifecycleService, auditService),
                new CcActionHandler(contextService, actorValidator, auditService, notificationService),
                new ReadCcActionHandler(contextService, auditService),
                new ReturnApprovalActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService),
                new WithdrawActionHandler(contextService, auditService, new WithdrawPolicyGuard(bundleRegistry, auditReader)),
                new UrgeActionHandler(contextService, auditService, notificationService,
                        new UrgeThrottle(bundleRegistry, auditReader)),
                new CommentActionHandler(contextService, auditService, accessGuard),
                new TerminateActionHandler(contextService, auditService, notificationService),
                new ReassignActionHandler(contextService, actorValidator, lifecycleService, auditService, notificationService));
    }
}

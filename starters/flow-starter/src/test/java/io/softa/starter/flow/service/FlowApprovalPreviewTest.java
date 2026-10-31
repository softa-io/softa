package io.softa.starter.flow.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowApprovalPreview.AutoApproval;
import io.softa.starter.flow.dto.FlowApprovalPreview.AutoApprovalReason;
import io.softa.starter.flow.dto.FlowApprovalPreview.Status;
import io.softa.starter.flow.dto.FlowApprovalPreview.Step;
import io.softa.starter.flow.dto.FlowApprovalPreview.StepOutcome;
import io.softa.starter.flow.dto.FlowApprovalPreview.Stop;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Actor;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.WithdrawPolicy;
import io.softa.starter.flow.runtime.LeaveRequest;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.spi.ApprovalSubmission;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.service.impl.FlowLaunchServiceImpl;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The approval path preview of a record: the flow a submission would get, its steps with their
 * approvers or why each passes on its own, the route decided where the submission already says how
 * and left undecided where only the running approval would — and nothing submitted, started or
 * written on the way.
 */
class FlowApprovalPreviewTest {

    private static final String MODEL = "LeaveRequest";
    private static final String ROW = "42";
    private static final String SUBMITTER = "1001";
    private static final String MANAGER = "2001";
    private static final String HR = "2002";
    private static final String DEPUTY = "2003";
    private static final Map<String, String> NAMES = Map.of(
            SUBMITTER, "Alice", MANAGER, "Bob", HR, "Carol", DEPUTY, "Dan");

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    private final ApprovalActionLedger ledger = mock(ApprovalActionLedger.class);
    private final ApprovalAuditReader auditReader = new ApprovalAuditReader(ledger);
    private final FlowAuditService auditService = new FlowAuditService(auditReader);
    private final ApproverResolutionService approverResolution = new ApproverResolutionService();
    private final PendingApprovalFactory pendingApprovalFactory = new PendingApprovalFactory(approverResolution, auditReader);
    private final FlowDelegationService delegations = mock(FlowDelegationService.class);
    private final ApprovalPathPreviewer previewer = new ApprovalPathPreviewer(new FlowGatewayRouter(),
            pendingApprovalFactory, approverResolution,
            new ApproverDedupService(new ApprovalLifecycleService(pendingApprovalFactory), auditService, auditReader),
            auditService);

    @SuppressWarnings("unchecked")
    private final ModelApprovalHandler<LeaveRequest> handler = mock(ModelApprovalHandler.class);
    /** Nothing may start: every interaction with the engine fails the test. */
    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final OrganizationService organization = mock(OrganizationService.class);
    private final List<FlowInstance> instances = new ArrayList<>();
    private final Map<String, Object> record = new LinkedHashMap<>(Map.of("id", 42L, "employeeId", 5001L, "leaveType", "Annual", "days", 3));
    private FlowLaunchServiceImpl launchService;
    private MockedStatic<ModelManager> modelManager;
    private MockedStatic<IdUtils> idUtils;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(MODEL)).thenReturn(true);
        idUtils = mockStatic(IdUtils.class);
        idUtils.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        AutoDelegationService autoDelegation = new AutoDelegationService();
        ReflectionTestUtils.setField(autoDelegation, "delegationService", delegations);
        ReflectionTestUtils.setField(pendingApprovalFactory, "autoDelegationService", autoDelegation);
        ObjectProvider<OrganizationService> organizationProvider = mock(ObjectProvider.class);
        when(organizationProvider.getIfAvailable()).thenReturn(organization);
        when(organization.getUsersByIds(anyList())).thenAnswer(invocation -> ((List<Long>) invocation.getArgument(0))
                .stream()
                .filter(id -> NAMES.containsKey(id.toString()))
                .map(id -> new ApproverInfo(id, NAMES.get(id.toString())))
                .toList());

        when(handler.modelClass()).thenReturn(LeaveRequest.class);
        when(handler.submission(any())).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of("days", 3)));
        // The submitter reads the record with their own permissions; the submit condition sees all of it.
        when(modelService.getById(eq(MODEL), any(), eq(List.of("id")))).thenReturn(Optional.of(Map.of("id", 42L)));
        when(modelService.getById(eq(MODEL), any())).thenAnswer(invocation -> Optional.of(record));
        when(instanceService.findByModelNameAndRowId(MODEL, ROW)).thenReturn(instances);

        launchService = new FlowLaunchServiceImpl(publishService, engine, bundleRegistry, compiler,
                // No status writer: a preview writes nothing.
                ModelApprovalHandlerRegistry.of(List.of(handler)), null, new DefaultFlowTriggerRegistry(bundleRegistry),
                instanceService, modelService, previewer,
                new FlowInstanceAccessGuard(instanceService, auditReader, modelService),
                new FlowUserNames(organizationProvider, modelService),
                new WithdrawPolicyGuard(bundleRegistry, auditReader));
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
        idUtils.close();
    }

    @Test
    void showsTheFlowTheRecordWouldGetAndWhoWouldBeAsked() {
        publishService.publish("annual", chain("Annual leave",
                approval("manager", "Manager", Map.of("approvers", List.of(MANAGER)))));

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(Status.READY, preview.getStatus());
        assertEquals(new FlowApprovalPreview.Flow("annual", "Annual leave"), preview.getFlow());
        Step step = preview.getSteps().getFirst();
        assertEquals(1, preview.getSteps().size());
        assertEquals("manager", step.getNodeId());
        assertEquals("Manager", step.getNodeLabel());
        assertEquals(StepOutcome.WAITING, step.getOutcome());
        assertEquals(List.of(new Actor(MANAGER, "Bob")), step.getApprovers());
        assertEquals(1, step.getRequiredApprovals());
        assertEquals(List.of(), step.getAutoApprovals());
        assertEquals(List.of(), preview.getUndecided());
        assertNull(preview.getFailure());
    }

    @Test
    void anApprovalInProgressShowsTheStepItWaitsOnThenTheRest() {
        DesignFlowDefinition design = chain("Annual leave", approval("manager", "Manager", Map.of("approvers", List.of(MANAGER))),
                approval("hr", "HR", Map.of("approvers", List.of(HR))));
        design.setWithdrawPolicy(WithdrawPolicy.UNTIL_COMPLETED);
        CompiledFlowDefinition flow = publishService.publish("annual", design);
        record.put("approvalStatus", "Pending");
        FlowInstance running = new FlowInstance();
        running.setInstanceId("run-1");
        running.setStatus(FlowExecutionStatus.WAITING);
        running.setRecordApproval(true);
        instances.add(running);
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("run-1").bundleId(flow.getBundleId()).flowId("annual")
                .initiatorId(SUBMITTER).recordApproval(true).modelName(MODEL).rowId(ROW)
                .status(FlowExecutionStatus.WAITING)
                .variables(new LinkedHashMap<>(Map.of("days", 3, "_subjectId", 5001L)))
                .completedNodeIds(new ArrayList<>(List.of("start")))
                .pendingApprovals(new ArrayList<>(List.of(PendingApproval.builder()
                        .nodeId("manager").approvers(new ArrayList<>(List.of(MANAGER)))
                        .approvedActors(new ArrayList<>()).rejectedActors(new ArrayList<>())
                        .requiredApprovalCount(1).build())))
                .approvalAuditDelta(new ArrayList<>())
                .joinArrivalCounts(new HashMap<>())
                .build();
        when(engine.getInstance("run-1")).thenReturn(Optional.of(state));

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(Status.IN_PROGRESS, preview.getStatus());
        assertEquals(new FlowApprovalPreview.Flow("annual", "Annual leave"), preview.getFlow());
        assertEquals(List.of("manager", "hr"), preview.getSteps().stream().map(Step::getNodeId).toList());
        Step current = preview.getSteps().getFirst();
        assertEquals(Boolean.TRUE, current.getCurrent());
        assertEquals(List.of(new Actor(MANAGER, "Bob")), current.getApprovers());
        Step next = preview.getSteps().get(1);
        assertNull(next.getCurrent());
        assertEquals(StepOutcome.WAITING, next.getOutcome());
        assertEquals(List.of(new Actor(HR, "Carol")), next.getApprovers());
        assertTrue(state.getApprovalAuditDelta().isEmpty(), "the running instance is never changed");
        verify(handler, never()).validate(any());
        assertEquals(Boolean.TRUE, preview.getWithdrawable(), "its submitter, on a flow that lets them");
        assertEquals(Boolean.FALSE, launchService.previewRecord(MODEL, ROW, MANAGER).getWithdrawable());
    }

    @Test
    void nothingIsSubmittedStartedOrWritten() {
        publishService.publish("annual", chain("Annual leave", approval("manager", "Manager", Map.of("approvers", List.of(MANAGER))),
                approval("self", "Self review", Map.of("approvers", List.of(SUBMITTER)))));
        FlowDelegation delegation = new FlowDelegation();
        delegation.setId(9L);
        delegation.setDelegatorId(MANAGER);
        delegation.setDelegateId(DEPUTY);
        when(delegations.findActiveDelegation(eq(MANAGER), any(), any(), any())).thenReturn(Optional.of(delegation));

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        // The step goes to the delegate, as it would once submitted…
        assertEquals(List.of(new Actor(DEPUTY, "Dan")), preview.getSteps().getFirst().getApprovers());
        // …but the delegation's use is not counted, and nothing else is written or sent.
        verify(delegations, never()).recordDelegationUsage(any());
        verify(handler).validate(any());
        verify(handler).submission(any());
        verify(handler, never()).afterSubmitted(any());
        verify(handler, never()).afterOutcome(any(), any());
        verifyNoInteractions(engine, ledger);
        verify(instanceService).findByModelNameAndRowId(MODEL, ROW);
        verifyNoMoreInteractions(instanceService);
    }

    @Test
    void theSubmittersOwnStepsPassAsTheirApprovalModesDecide() {
        publishService.publish("annual", chain("Annual leave", approval("own", "Own", Map.of("approvers", List.of(SUBMITTER))),
                approval("anyOne", "Any one", Map.of("approvers", List.of(SUBMITTER, MANAGER))),
                approval("all", "All", Map.of("approvers", List.of(SUBMITTER, HR), "approvalMode", "Unanimous"))));

        List<Step> steps = launchService.previewRecord(MODEL, ROW, SUBMITTER).getSteps();

        AutoApproval bySubmitter = new AutoApproval(new Actor(SUBMITTER, "Alice"), AutoApprovalReason.SUBMITTER);
        assertEquals(StepOutcome.AUTO_APPROVED, steps.get(0).getOutcome());
        assertEquals(List.of(bySubmitter), steps.get(0).getAutoApprovals());
        assertEquals(List.of(), steps.get(0).getApprovers());
        // An any-one step the submitter shares with others passes…
        assertEquals(StepOutcome.AUTO_APPROVED, steps.get(1).getOutcome());
        assertEquals(List.of(), steps.get(1).getApprovers());
        // …an all-approve one still waits for the others.
        assertEquals(StepOutcome.WAITING, steps.get(2).getOutcome());
        assertEquals(List.of(new Actor(HR, "Carol")), steps.get(2).getApprovers());
        assertEquals(1, steps.get(2).getRequiredApprovals());
        assertEquals(List.of(bySubmitter), steps.get(2).getAutoApprovals());
    }

    @Test
    void stepsWithNobodyToApproveFollowTheirEmptyApproverStrategy() {
        // No organization service resolves approvers here, so a supervisor source resolves to nobody.
        Map<String, Object> supervisor = Map.of("type", "Supervisor", "level", 1);
        publishService.publish("annual", chain("Annual leave", approval("auto", "Auto", Map.of("approverSource", supervisor, "emptyApproverStrategy", "AutoApprove")),
                approval("skip", "Skip", Map.of("approverSource", supervisor, "emptyApproverStrategy", "Skip")),
                approval("stop", "Stop", Map.of("approverSource", supervisor, "emptyApproverStrategy", "Error")),
                approval("manager", "Manager", Map.of("approvers", List.of(MANAGER)))));

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(List.of(StepOutcome.NO_APPROVER_APPROVED, StepOutcome.NO_APPROVER_SKIPPED,
                StepOutcome.NO_APPROVER_FAILS), outcomes(preview));
        // The approval would stop there with an error: the steps after it are never reached.
        assertEquals(new Stop("stop", "Stop", "Approval node 'stop' resolved an empty approver list (strategy=ERROR)"),
                preview.getFailure());
    }

    @Test
    void theRouteIsDecidedByWhatTheSubmissionSays() {
        publishService.publish("annual", routedFlow("days > 5", null));

        assertEquals(List.of("manager"), nodeIds(launchService.previewRecord(MODEL, ROW, SUBMITTER)));

        when(handler.submission(any())).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of("days", 7)));
        assertEquals(List.of("hr"), nodeIds(launchService.previewRecord(MODEL, ROW, SUBMITTER)));
    }

    @Test
    void aRuleRouteIsDecidedOverTheRecordsOwnFields() {
        MetaField days = mock(MetaField.class);
        when(days.getFieldName()).thenReturn("days");
        when(days.getFieldType()).thenReturn(FieldType.INTEGER);
        modelManager.when(() -> ModelManager.getModelFields(MODEL)).thenReturn(List.of(days));
        modelManager.when(() -> ModelManager.existField(MODEL, "days")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelField(MODEL, "days")).thenReturn(days);
        // The handler adds nothing: the route reads the record's own field, which every approval carries.
        when(handler.submission(any())).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of()));
        publishService.publish("annual", ruledFlow(Filters.of(List.of("days", ">", 5))));

        assertEquals(List.of("manager"), nodeIds(launchService.previewRecord(MODEL, ROW, SUBMITTER)));

        record.put("days", 7);
        assertEquals(List.of("hr"), nodeIds(launchService.previewRecord(MODEL, ROW, SUBMITTER)));
    }

    @Test
    void aRouteThatReadsWhatANodeComputesWhenTheApprovalRunsIsUndecided() {
        publishService.publish("annual", routedFlow("weight > 5", script("weigh", "days * 2", "weight")));

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(List.of(), preview.getSteps());
        assertEquals(List.of(new Stop("weigh", "weigh", null)), preview.getUndecided());
        assertNull(preview.getFailure());
    }

    @Test
    void aRouteThatReadsOnlyWhatTheSubmissionSaysIsDecidedPastSuchANode() {
        publishService.publish("annual", routedFlow("days > 5", script("weigh", "days * 2", "weight")));

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(List.of("manager"), nodeIds(preview));
        assertEquals(List.of(), preview.getUndecided());
    }

    @Test
    void aStepWhoseApproversComeFromWhatANodeComputesIsUndetermined() {
        DesignFlowDefinition design = chain("Annual leave", script("pick", "seq.list('" + HR + "')", "reviewers"),
                approval("review", "Review", Map.of("approverSource", Map.of("type", "VariableList", "variable", "reviewers"))),
                approval("manager", "Manager", Map.of("approvers", List.of(MANAGER))));
        // Without dedup, whoever reviews has no bearing on the manager's step.
        design.setApproverDedup(ApproverDedupStrategy.NONE);
        publishService.publish("annual", design);

        List<Step> steps = launchService.previewRecord(MODEL, ROW, SUBMITTER).getSteps();

        assertEquals(StepOutcome.UNDETERMINED, steps.get(0).getOutcome());
        assertEquals(List.of(), steps.get(0).getApprovers());
        assertEquals(StepOutcome.WAITING, steps.get(1).getOutcome());
        assertEquals(List.of(new Actor(MANAGER, "Bob")), steps.get(1).getApprovers());
    }

    @Test
    void anApproverWhoApprovedAnEarlierStepPassesALaterOne() {
        publishService.publish("annual", chain("Annual leave", approval("manager", "Manager", Map.of("approvers", List.of(MANAGER))),
                approval("review", "Review", Map.of("approvers", List.of(MANAGER, HR)))));

        List<Step> steps = launchService.previewRecord(MODEL, ROW, SUBMITTER).getSteps();

        assertEquals(StepOutcome.WAITING, steps.get(0).getOutcome());
        assertEquals(StepOutcome.AUTO_APPROVED, steps.get(1).getOutcome());
        assertEquals(List.of(new AutoApproval(new Actor(MANAGER, "Bob"), AutoApprovalReason.EARLIER_APPROVAL)),
                steps.get(1).getAutoApprovals());
    }

    @Test
    void whetherAnEarlierApprovalPassesAStepIsUndeterminedWhenAnyOfSeveralCouldHaveGivenIt() {
        publishService.publish("annual", chain("Annual leave", approval("panel", "Panel", Map.of("approvers", List.of(MANAGER, HR))),
                approval("hr", "HR", Map.of("approvers", List.of(HR)))));

        List<Step> steps = launchService.previewRecord(MODEL, ROW, SUBMITTER).getSteps();

        assertEquals(StepOutcome.WAITING, steps.get(0).getOutcome());
        assertEquals(List.of(new Actor(MANAGER, "Bob"), new Actor(HR, "Carol")), steps.get(0).getApprovers());
        assertEquals(1, steps.get(0).getRequiredApprovals());
        // Carol passes it on her own if she approved the panel's step, and is asked if Bob did.
        assertEquals(StepOutcome.UNDETERMINED, steps.get(1).getOutcome());
        assertEquals(List.of(new Actor(HR, "Carol")), steps.get(1).getApprovers());
    }

    @Test
    void withoutApproverDedupALaterStepWaitsWhoeverApprovedEarlier() {
        DesignFlowDefinition design = chain("Annual leave", approval("panel", "Panel", Map.of("approvers", List.of(MANAGER, HR))),
                approval("hr", "HR", Map.of("approvers", List.of(HR))));
        design.setApproverDedup(ApproverDedupStrategy.NONE);
        publishService.publish("annual", design);

        List<Step> steps = launchService.previewRecord(MODEL, ROW, SUBMITTER).getSteps();

        assertEquals(StepOutcome.WAITING, steps.get(1).getOutcome());
    }

    @Test
    void parallelBranchesJoinBeforeTheStepAfterThem() {
        List<FlowGraphNode> nodes = List.of(node("start", FlowNodeType.START), node("fork", FlowNodeType.PARALLEL_FORK),
                approval("finance", "Finance", Map.of("approvers", List.of(MANAGER))),
                approval("hr", "HR", Map.of("approvers", List.of(HR))),
                node("join", FlowNodeType.PARALLEL_JOIN),
                approval("final", "Final", Map.of("approvers", List.of(DEPUTY))),
                node("end", FlowNodeType.END));
        List<FlowGraphEdge> edges = List.of(edge("e1", "start", "fork", null), edge("e2", "fork", "finance", null),
                edge("e3", "fork", "hr", null), edge("e4", "finance", "join", null), edge("e5", "hr", "join", null),
                edge("e6", "join", "final", null), edge("e7", "final", "end", null));
        publishService.publish("annual", flow("Annual leave", nodes, edges));

        assertEquals(List.of("finance", "hr", "final"), nodeIds(launchService.previewRecord(MODEL, ROW, SUBMITTER)));
    }

    @Test
    void theRecordsOwnRefusalIsReportedNotThrown() {
        publishService.publish("annual", chain("Annual leave", approval("manager", "Manager", Map.of("approvers", List.of(MANAGER)))));
        doThrow(new BusinessException("Only the requester can submit this leave.")).when(handler).validate(any());

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(Status.REFUSED, preview.getStatus());
        assertEquals("Only the requester can submit this leave.", preview.getMessage());
        assertNull(preview.getSteps());
    }

    @Test
    void aRecordAlreadyInApprovalIsReportedAsRefused() {
        publishService.publish("annual", chain("Annual leave", approval("manager", "Manager", Map.of("approvers", List.of(MANAGER)))));
        FlowInstance inProgress = new FlowInstance();
        inProgress.setRecordApproval(true);
        inProgress.setStatus(FlowExecutionStatus.WAITING);
        instances.add(inProgress);

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(Status.REFUSED, preview.getStatus());
        assertEquals("This record is already in approval.", preview.getMessage());
    }

    @Test
    void aRecordPendingOrApprovedIsReportedAsRefusedBeforeItsHandlerIsAsked() {
        publishService.publish("annual", chain("Annual leave", approval("manager", "Manager", Map.of("approvers", List.of(MANAGER)))));

        record.put("approvalStatus", "Pending");
        FlowApprovalPreview pending = launchService.previewRecord(MODEL, ROW, SUBMITTER);
        record.put("approvalStatus", "Approved");
        FlowApprovalPreview approved = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(Status.REFUSED, pending.getStatus());
        assertEquals("This record is already in approval.", pending.getMessage());
        assertEquals(Status.REFUSED, approved.getStatus());
        assertEquals("This record is approved and cannot be submitted again.", approved.getMessage());
        verify(handler, never()).validate(any());
    }

    @Test
    void noEnabledFlowIsReportedNotThrown() {
        publishService.publish("sick", chain("Sick leave",
                approval("hr", "HR", Map.of("approvers", List.of(HR)))));
        bundleRegistry.disable("sick");

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(Status.NO_FLOW, preview.getStatus());
        assertTrue(preview.getMessage().contains("No approval flow applies"), preview.getMessage());
    }

    @Test
    void severalFlowsTakingTheRecordAreReportedNotThrown() {
        // Two conditions of their own — publishing refuses the same one twice — that both hold for the record
        DesignFlowDefinition first = chain("Flow A", approval("manager", "Manager", Map.of("approvers", List.of(MANAGER))));
        first.setTrigger(new SubmitTrigger(MODEL, "true"));
        DesignFlowDefinition second = chain("Flow B", approval("hr", "HR", Map.of("approvers", List.of(HR))));
        second.setTrigger(new SubmitTrigger(MODEL, "1 == 1"));
        publishService.publish("a", first);
        publishService.publish("b", second);

        FlowApprovalPreview preview = launchService.previewRecord(MODEL, ROW, SUBMITTER);

        assertEquals(Status.SEVERAL_FLOWS, preview.getStatus());
        assertTrue(preview.getMessage().contains("Several approval flows"), preview.getMessage());
        assertEquals(List.of("Flow A", "Flow B"), preview.getCandidates().stream()
                .map(FlowApprovalPreview.Flow::getFlowName).sorted().toList());
        assertNull(preview.getSteps());
    }

    @Test
    void onlyThoseWhoCanReadTheRecordMayPreviewIt() {
        publishService.publish("annual", chain("Annual leave", approval("manager", "Manager", Map.of("approvers", List.of(MANAGER)))));
        when(modelService.getById(eq(MODEL), any(), eq(List.of("id")))).thenReturn(Optional.empty());

        assertThrows(FlowAuthorizationException.class, () -> launchService.previewRecord(MODEL, ROW, SUBMITTER));
        verify(handler, never()).validate(any());
    }

    private static List<StepOutcome> outcomes(FlowApprovalPreview preview) {
        return preview.getSteps().stream().map(Step::getOutcome).toList();
    }

    private static List<String> nodeIds(FlowApprovalPreview preview) {
        return preview.getSteps().stream().map(Step::getNodeId).toList();
    }

    /**
     * start → [{@code before} →] a step for HR when {@code condition} holds, else one for the
     * manager → end; the route is decided on the edges out of the node before the steps.
     */
    private static DesignFlowDefinition routedFlow(String condition, FlowGraphNode before) {
        List<FlowGraphNode> nodes = new ArrayList<>(List.of(node("start", FlowNodeType.START),
                approval("hr", "HR", Map.of("approvers", List.of(HR))),
                approval("manager", "Manager", Map.of("approvers", List.of(MANAGER))),
                node("end", FlowNodeType.END)));
        List<FlowGraphEdge> edges = new ArrayList<>(List.of(edge("e3", "hr", "end", null),
                edge("e4", "manager", "end", null)));
        String router = "start";
        if (before != null) {
            nodes.add(before);
            edges.add(edge("e0", "start", before.getId(), null));
            router = before.getId();
        }
        edges.add(edge("e1", router, "hr", condition));
        edges.add(edge("e2", router, "manager", null));
        return flow("Annual leave", nodes, edges);
    }

    /** {@link #routedFlow} whose HR edge holds a rule condition instead of an expression. */
    private static DesignFlowDefinition ruledFlow(Filters rule) {
        DesignFlowDefinition design = routedFlow(null, null);
        design.getGraph().getEdges().stream()
                .filter(edge -> "e1".equals(edge.getId()))
                .findFirst().orElseThrow()
                .setCondition(rule);
        return design;
    }

    /** start → each of {@code steps} in turn → end. */
    private static DesignFlowDefinition chain(String name, FlowGraphNode... steps) {
        List<FlowGraphNode> nodes = new ArrayList<>();
        List<FlowGraphEdge> edges = new ArrayList<>();
        nodes.add(node("start", FlowNodeType.START));
        String previous = "start";
        for (FlowGraphNode step : steps) {
            nodes.add(step);
            edges.add(edge("e" + edges.size(), previous, step.getId(), null));
            previous = step.getId();
        }
        nodes.add(node("end", FlowNodeType.END));
        edges.add(edge("e" + edges.size(), previous, "end", null));
        return flow(name, nodes, edges);
    }

    private static DesignFlowDefinition flow(String name, List<FlowGraphNode> nodes, List<FlowGraphEdge> edges) {
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger(MODEL))
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }

    private static FlowGraphNode approval(String id, String label, Map<String, Object> config) {
        return FlowGraphNode.builder().id(id).type(FlowNodeType.APPROVAL).label(label).config(config).build();
    }

    private static FlowGraphNode script(String id, String expression, String outputVariable) {
        return FlowGraphNode.builder().id(id).type(FlowNodeType.SCRIPT).label(id)
                .config(Map.of("expression", expression, "outputVariable", outputVariable))
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target, String condition) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).conditionExpression(condition).build();
    }
}

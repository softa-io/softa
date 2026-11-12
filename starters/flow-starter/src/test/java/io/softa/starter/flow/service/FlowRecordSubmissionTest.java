package io.softa.starter.flow.service;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.AccessScope;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.*;
import io.softa.starter.flow.runtime.*;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.context.RecordModelFixture;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.exception.FlowNoApproverException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.ApprovalOutcome;
import io.softa.starter.flow.runtime.spi.ApprovalSubmission;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.FlowExecutionStateMapper;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.service.impl.FlowLaunchServiceImpl;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.argThat;

/**
 * Record submission end to end on the in-memory engine: the submit trigger chooses the flow, the
 * framework moves the record's approval status and calls its handler in order, approver steps skip the
 * submitter, and every result reaches the record through the outcome listener — once.
 */
class FlowRecordSubmissionTest {

    private static final String MODEL = "LeaveRequest";
    private static final String ROW = "42";
    private static final String SUBMITTER = "1001";
    private static final String MANAGER = "2001";

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(compiler, bundleRegistry);
    /** When each instance was first saved: the store's creation order, which the stub does not keep. */
    private final Map<String, LocalDateTime> createdAt = new HashMap<>();
    private final StubFlowInstanceStore instanceStore = new StubFlowInstanceStore() {
        @Override
        public FlowExecutionState save(FlowExecutionState state) {
            createdAt.putIfAbsent(state.getInstanceId(), LocalDateTime.now().plusNanos(createdAt.size()));
            return super.save(state);
        }
    };
    private final ApprovalAuditReader auditReader = new ApprovalAuditReader(new NoopApprovalActionLedger());
    @SuppressWarnings("unchecked")
    private final ModelApprovalHandler<LeaveRequest> handler = mock(ModelApprovalHandler.class);
    private final ModelApprovalHandlerRegistry handlerRegistry = ModelApprovalHandlerRegistry.of(List.of(handler));
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final ApprovalStatusWriter statusWriter = new ApprovalStatusWriter(modelService);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.create(
            bundleRegistry, instanceStore,
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler, state -> {},
            new ApprovalOutcomeListener(handlerRegistry, statusWriter));
    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    // The preview's collaborators stay null: FlowApprovalPreviewTest covers it.
    private final FlowLaunchServiceImpl launchService = new FlowLaunchServiceImpl(
            engine, bundleRegistry, compiler, handlerRegistry, statusWriter,
            new DefaultFlowTriggerRegistry(bundleRegistry), instanceService, modelService, null, null, null,
            new WithdrawPolicyGuard(bundleRegistry, new ApprovalAuditReader(new NoopApprovalActionLedger()),
                    new FailedApprovalRecords(instanceService, modelService)));

    /** The record as its table holds it: reads return a copy, updates land on it. */
    private final Map<String, Object> record = new LinkedHashMap<>(
            Map.of("id", 42L, "employeeId", 5001L, "leaveType", "Annual", "days", 3, "approvalStatus", "Draft"));
    private MockedStatic<IdUtils> idUtils;

    @BeforeEach
    void setUp() {
        idUtils = mockStatic(IdUtils.class);
        idUtils.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(handler.modelClass()).thenReturn(LeaveRequest.class);
        when(handler.submission(any())).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of("days", 3)));
        // The submitter reads the record with their own permissions; the submission reads all of it.
        when(modelService.getById(eq(MODEL), any(), eq(List.of("id")))).thenReturn(Optional.of(Map.of("id", 42L)));
        when(modelService.getById(eq(MODEL), any())).thenAnswer(invocation -> Optional.of(new LinkedHashMap<>(record)));
        when(modelService.updateOne(eq(MODEL), any())).thenAnswer(invocation -> {
            Map<String, Object> patch = invocation.getArgument(1);
            patch.forEach((field, value) -> {
                if (!"id".equals(field)) {
                    record.put(field, value);
                }
            });
            return true;
        });
        // The instance table, as the store holds it: a record's latest approval, its row whole.
        when(instanceService.findLatestRecordApproval(anyString(), anyString())).thenAnswer(invocation -> {
            List<FlowInstance> instances = new ArrayList<>();
            for (FlowExecutionStatus status : FlowExecutionStatus.values()) {
                for (FlowExecutionState state : instanceStore.listByStatus(status)) {
                    if (Objects.equals(state.getModelName(), invocation.getArgument(0))
                            && Objects.equals(state.getRowId(), invocation.getArgument(1))
                            && Boolean.TRUE.equals(state.getRecordApproval())) {
                        FlowInstance instance = FlowExecutionStateMapper.toEntity(state);
                        instance.setCreatedTime(createdAt.get(state.getInstanceId()));
                        instances.add(instance);
                    }
                }
            }
            return instances.stream().max(Comparator.comparing(FlowInstance::getCreatedTime,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
        });
    }

    @AfterEach
    void tearDown() {
        idUtils.close();
    }

    @Test
    void submitStartsTheApprovalFlowOfTheRecordsDocument() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        assertEquals("annual", state.getFlowId());
        assertEquals("Leave of Alice", state.getTitle());
        assertEquals(SUBMITTER, state.getInitiatorId());
        assertEquals(MODEL, state.getModelName());
        assertEquals(ROW, state.getRowId());
        assertEquals(3, state.getVariables().get("days"));
        assertEquals("Submit", state.getVariables().get("_triggerType"));
        assertEquals(5001L, state.getVariables().get("_subjectId"));
        assertEquals(List.of(MANAGER), state.getPendingApprovals().getFirst().getApprovers());
        assertTrue(state.isApprovalOfRecord());
        assertEquals("Pending", record.get("approvalStatus"));
        assertEquals(1001L, record.get("submitterId"));
        assertNotNull(record.get("submittedTime"));
        InOrder order = inOrder(handler);
        order.verify(handler).validate(argThat(leave -> leave.getDays() == 3 && "Annual".equals(leave.getLeaveType())));
        order.verify(handler).submission(any());
        order.verify(handler).afterSubmitted(argThat(leave -> leave.getApprovalStatus() == ApprovalStatus.PENDING));
        verify(handler, never()).afterOutcome(any(), any());
    }

    @Test
    void aStepWithNobodyToApproveRefusesTheSubmissionWhole() {
        // No organization service here, so its supervisor source finds nobody; the empty-approver strategy is Error
        // unless set. (A step that names nobody at all is not published.)
        DesignFlowDefinition design = approvalFlow("Annual leave", List.of(MANAGER));
        design.getGraph().getNodes().set(1, FlowGraphNode.builder()
                .id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                .config(Map.of("approverSources", List.of(Map.of("type", "Supervisor"))))
                .build());
        publishService.publish("annual", design);

        FlowNoApproverException refused = assertThrows(FlowNoApproverException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));

        assertEquals("No approver was found for step 'Manager'. Ask your HR administrator to set one up, then try again.",
                refused.getMessage());
        // The submission's transaction rolls back the record with it; the engine keeps no failed instance either.
        assertTrue(instanceStore.listByStatus(FlowExecutionStatus.FAILED).isEmpty());
        assertTrue(createdAt.isEmpty(), "no instance was saved");
        verify(handler, never()).afterOutcome(any(), any());
    }

    @Test
    void theApprovalKeepsWhatTheRecordShowsInTheApprovalLists() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        when(handler.submission(any())).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of("days", 3),
                LocalDate.of(2026, 11, 2), "LR-0007", "Annual Leave · 3 days · 2–4 Nov"));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(5001L, state.getSubjectId());
        assertEquals(LocalDate.of(2026, 11, 2), state.getEffectiveDate());
        assertEquals("LR-0007", state.getRecordCode());
        // What the record asks for, as its handler words it: the line the approval lists show.
        assertEquals("Annual Leave · 3 days · 2–4 Nov", state.getSummary());
        // No organization service here, so no department to keep.
        assertNull(state.getDepartmentId());
    }

    @Test
    void aRecordTheSubmitterCannotReadIsNotSubmitted() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        when(modelService.getById(eq(MODEL), any(), eq(List.of("id")))).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));

        verify(handler, never()).validate(any());
        assertEquals("Draft", record.get("approvalStatus"));
    }

    /**
     * Submitting writes the record, so the submitter's reach is the range they may edit it in: a role that only views
     * a department's requests submits none of them.
     */
    @Test
    void theSubmittersReachIsTheRangeTheyMayEditTheRecordIn() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        List<AccessType> readAs = recordReachReadAs();

        launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(List.of(AccessType.UPDATE), readAs);
    }

    /** Cancelling writes the record too: the applicant's reach is the range they may edit it in. */
    @Test
    void theApplicantsReachForACancellationIsTheRangeTheyMayEditTheRecordIn() {
        List<AccessType> readAs = recordReachReadAs();

        launchService.cancelRecord(MODEL, ROW);

        assertEquals(List.of(AccessType.UPDATE), readAs);
    }

    /** The action each check of the caller's reach on the record is read for, as the read sees it. */
    private List<AccessType> recordReachReadAs() {
        List<AccessType> readAs = new ArrayList<>();
        when(modelService.getById(eq(MODEL), any(), eq(List.of("id")))).thenAnswer(invocation -> {
            readAs.add(AccessScope.currentFor(MODEL));
            return Optional.of(Map.of("id", 42L));
        });
        return readAs;
    }

    @Test
    void theHandlersRefusalLeavesTheRecordAsItWas() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        doThrow(new BusinessException("Only the requester can submit this leave.")).when(handler).validate(any());

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));

        assertEquals("Only the requester can submit this leave.", error.getMessage());
        assertEquals("Draft", record.get("approvalStatus"));
        verify(modelService, never()).updateOne(anyString(), any());
        verify(handler, never()).afterSubmitted(any());
        assertTrue(instanceStore.listByStatus(FlowExecutionStatus.WAITING).isEmpty());
    }

    @Test
    void aRecordThatNamesNoEmployeeCannotBeSubmitted() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        record.remove("employeeId");

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));

        assertTrue(error.getMessage().contains("employeeId"), error.getMessage());
        verify(handler, never()).validate(any());
    }

    @Test
    void anApprovedRecordCannotBeSubmittedAgain() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        record.put("approvalStatus", "Approved");

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));

        assertTrue(error.getMessage().contains("approved"), error.getMessage());
        verify(handler, never()).validate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Rejected", "Cancelled"})
    void aRejectedOrCancelledRecordIsFinal(String status) {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        record.put("approvalStatus", status);

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));

        assertTrue(error.getMessage().contains(status.toLowerCase()), error.getMessage());
        verify(handler, never()).validate(any());
        assertEquals(status, record.get("approvalStatus"));
    }

    @Test
    void aDraftIsCancelledForGood() {
        launchService.cancelRecord(MODEL, ROW);

        assertEquals("Cancelled", record.get("approvalStatus"));
        InOrder order = inOrder(handler);
        order.verify(handler).validateCancel(any());
        order.verify(handler).afterOutcome(argThat(leave -> leave.getApprovalStatus() == ApprovalStatus.CANCELLED),
                eq(new ApprovalOutcome(ApprovalOutcomeType.CANCELLED, null)));
        // A returned approval of it no longer waits for the record to be revised.
        verify(instanceService).markSuperseded(MODEL, ROW);
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        assertThrows(BusinessException.class, () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pending", "Approved", "Rejected", "Cancelled"})
    void onlyADraftIsCancelled(String status) {
        record.put("approvalStatus", status);

        assertThrows(BusinessException.class, () -> launchService.cancelRecord(MODEL, ROW));

        assertEquals(status, record.get("approvalStatus"));
        verify(handler, never()).validateCancel(any());
        verify(handler, never()).afterOutcome(any(), any());
    }

    @Test
    void theHandlerOrTheApplicantsOwnPermissionsRefuseACancellation() {
        doThrow(new BusinessException("Only the claimant can cancel this claim.")).when(handler).validateCancel(any());

        BusinessException refused = assertThrows(BusinessException.class,
                () -> launchService.cancelRecord(MODEL, ROW));
        when(modelService.getById(eq(MODEL), any(), eq(List.of("id")))).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> launchService.cancelRecord(MODEL, ROW));

        assertTrue(refused.getMessage().contains("claimant"), refused.getMessage());
        assertEquals("Draft", record.get("approvalStatus"));
        verify(handler, never()).afterOutcome(any(), any());
    }

    @Test
    void submitIsRefusedWhenTheDocumentHasNoApprovalFlow() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        assertTrue(error.getMessage().contains("No approval flow applies"), error.getMessage());
        verify(handler, never()).afterSubmitted(any());
    }

    @Test
    void submitIsRefusedWhileTheDocumentsApprovalFlowIsDisabled() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        bundleRegistry.disable("annual");

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        assertTrue(error.getMessage().contains("No approval flow applies"), error.getMessage());
        assertEquals("Draft", record.get("approvalStatus"));
    }

    @Test
    void submitIsRefusedWhenSeveralFlowsOfTheDocumentTakeTheRecord() {
        // Two conditions of their own — publishing refuses the same one twice — that both hold for the record
        publishService.publish("a", flow("Flow A", new SubmitTrigger(MODEL, "true"), List.of(MANAGER)));
        publishService.publish("b", flow("Flow B", new SubmitTrigger(MODEL, "1 == 1"), List.of(MANAGER)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        assertTrue(error.getMessage().contains("Several approval flows"), error.getMessage());
        verify(handler, never()).afterSubmitted(any());
    }

    @Test
    void theFlowWithoutAConditionTakesWhatNoOtherFlowTakes() {
        // The document's default, beside a copy for long leave: each record goes to exactly one of them.
        publishService.publish("default", flow("Leave", new SubmitTrigger(MODEL), List.of(MANAGER)));
        publishService.publish("long", flow("Long leave", new SubmitTrigger(MODEL, "days > 5"), List.of(MANAGER)));

        assertEquals("default", launchService.submitRecord(MODEL, ROW, SUBMITTER).getFlowId());

        launchService.withdrawRecord(MODEL, ROW, SUBMITTER, "make it two weeks");
        when(handler.submission(any())).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of("days", 10)));
        assertEquals("long", launchService.submitRecord(MODEL, ROW, SUBMITTER).getFlowId());
    }

    @Test
    void aDefaultFlowDoesNotSettleConditionsThatOverlap() {
        publishService.publish("default", flow("Leave", new SubmitTrigger(MODEL), List.of(MANAGER)));
        publishService.publish("a", flow("Flow A", new SubmitTrigger(MODEL, "true"), List.of(MANAGER)));
        publishService.publish("b", flow("Flow B", new SubmitTrigger(MODEL, "1 == 1"), List.of(MANAGER)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        assertTrue(error.getMessage().contains("Several approval flows"), error.getMessage());
    }

    @Test
    void aRecordAlreadyInApprovalCannotBeSubmittedAgain() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        launchService.submitRecord(MODEL, ROW, SUBMITTER);

        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        assertTrue(error.getMessage().contains("already in approval"), error.getMessage());
    }

    @Test
    void anAutomationRunOnTheRecordNeitherBlocksSubmissionNorWritesBack() {
        publishService.publish("automation", flow("Automation", new ApiTrigger(null), null));
        FlowStartRequest automation = new FlowStartRequest();
        automation.setFlowId("automation");
        automation.setInitiatorId(SUBMITTER);
        automation.setModelName(MODEL);
        automation.setRowId(ROW);
        assertEquals(FlowExecutionStatus.COMPLETED, engine.startAutomation(automation).getStatus());
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));

        assertEquals(FlowExecutionStatus.WAITING, launchService.submitRecord(MODEL, ROW, SUBMITTER).getStatus());

        verify(handler, never()).afterOutcome(any(), any());
    }

    @Test
    void theSubmitFlowIsNotStartedByAnApiStart() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        FlowStartRequest apiStart = new FlowStartRequest();
        apiStart.setFlowId("annual");
        apiStart.setInitiatorId(SUBMITTER);
        apiStart.setModelName(MODEL);
        apiStart.setRowId(ROW);

        assertThrows(FlowActionValidationException.class, () -> engine.startAutomation(apiStart));
        verify(handler, never()).afterSubmitted(any());
    }

    @Test
    void theSubmitFlowStartedAnotherWayIsNotTheRecordsApproval() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        // The same flow started on the same record without a submission.
        FlowStartRequest unsubmitted = new FlowStartRequest();
        unsubmitted.setFlowId("annual");
        unsubmitted.setInitiatorId(SUBMITTER);
        unsubmitted.setModelName(MODEL);
        unsubmitted.setRowId(ROW);
        FlowExecutionState other = engine.start(unsubmitted);
        assertFalse(other.isApprovalOfRecord());

        engine.approve(nodeRequest(new FlowApproveRequest(), other.getInstanceId(), MANAGER, "fine"));

        verify(handler, never()).afterOutcome(any(), any());
        assertEquals(FlowExecutionStatus.WAITING, launchService.submitRecord(MODEL, ROW, SUBMITTER).getStatus());
    }

    @Test
    void approvalWritesApprovedBack() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        engine.approve(nodeRequest(new FlowApproveRequest(), started.getInstanceId(), MANAGER, "enjoy"));

        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.APPROVED, started.getInstanceId()),
                capturedOutcome());
        assertEquals("Approved", record.get("approvalStatus"));
        verify(handler).afterOutcome(argThat(leave -> leave.getApprovalStatus() == ApprovalStatus.APPROVED), any());
    }

    @Test
    void rejectionWritesRejectedBack() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        engine.reject(nodeRequest(new FlowRejectRequest(), started.getInstanceId(), MANAGER, "busy week"));

        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.REJECTED, started.getInstanceId()),
                capturedOutcome());
        assertEquals("Rejected", record.get("approvalStatus"));
    }

    /**
     * The CCs an approval's result sends are recorded after the entry that ended it, in the status it ended in:
     * the result still reaches the record, once (zingkey-hcm#2584 — the record stayed Pending).
     */
    @Test
    void anApprovalThatCcsOnItsResultStillWritesApprovedBack() {
        publishService.publish("annual", withCc(approvalFlow("Annual leave", List.of(MANAGER)),
                ccRule(CcTiming.ON_APPROVED, "3001")));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        FlowExecutionState completed = engine.approve(
                nodeRequest(new FlowApproveRequest(), started.getInstanceId(), MANAGER, "enjoy"));

        assertEquals(FlowExecutionStatus.COMPLETED, completed.getStatus());
        assertEquals(ApprovalActionType.CC, completed.getApprovalAuditDelta().getLast().getAction(),
                "the approval's CC is the last entry it recorded");
        assertEquals("Approved", record.get("approvalStatus"));
        verify(handler, times(1)).afterOutcome(argThat(leave -> leave.getApprovalStatus() == ApprovalStatus.APPROVED),
                eq(new ApprovalOutcome(ApprovalOutcomeType.APPROVED, started.getInstanceId())));
    }

    @Test
    void aRejectionThatCcsOnItsResultStillWritesRejectedBack() {
        publishService.publish("annual", withCc(approvalFlow("Annual leave", List.of(MANAGER)),
                ccRule(CcTiming.ON_REJECTED, "3001")));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        FlowExecutionState rejected = engine.reject(
                nodeRequest(new FlowRejectRequest(), started.getInstanceId(), MANAGER, "busy week"));

        assertEquals(ApprovalActionType.CC, rejected.getApprovalAuditDelta().getLast().getAction());
        assertEquals("Rejected", record.get("approvalStatus"));
        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.REJECTED, started.getInstanceId()), capturedOutcome());
        verify(handler, times(1)).afterOutcome(any(), any());
    }

    /** A step nobody resolves to that passes on its own, then the approval CCs: approved as it is submitted. */
    @Test
    void anApprovalThatPassesOnItsOwnAndCcsWritesApprovedBackAsItIsSubmitted() {
        DesignFlowDefinition design = withCc(approvalFlow("Annual leave", List.of(MANAGER)),
                ccRule(CcTiming.ON_APPROVED, "3001"));
        // No organization service here: the supervisor source finds nobody, and the step passes.
        design.getGraph().getNodes().set(1, FlowGraphNode.builder()
                .id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                .config(Map.of("approverSources", List.of(Map.of("type", "Supervisor")),
                        "emptyApproverStrategy", "AutoApprove"))
                .build());
        publishService.publish("annual", design);

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
        assertEquals("Approved", record.get("approvalStatus"));
        verify(handler, times(1)).afterOutcome(any(),
                eq(new ApprovalOutcome(ApprovalOutcomeType.APPROVED, state.getInstanceId())));
    }

    /**
     * A CC read changes no status: read on an approval returned before the record was submitted again, it writes
     * nothing — the record stays in its new approval.
     */
    @Test
    void aCcReadOnAReturnedApprovalLeavesTheRecordsNewApprovalPending() {
        publishService.publish("annual", withCc(approvalFlow("Annual leave", List.of(MANAGER)),
                ccRule(CcTiming.ON_SUBMIT, "3001")));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);
        engine.returnApproval(nodeRequest(new FlowReturnRequest(), started.getInstanceId(), MANAGER, "fix the dates"));
        launchService.submitRecord(MODEL, ROW, SUBMITTER);
        assertEquals("Pending", record.get("approvalStatus"));

        FlowCcReadRequest read = new FlowCcReadRequest();
        read.setInstanceId(started.getInstanceId());
        read.setNodeId("start");
        read.setActorId("3001");
        engine.readCc(read);

        assertEquals("Pending", record.get("approvalStatus"));
        // The return alone reached the record.
        verify(handler, times(1)).afterOutcome(any(), any());
    }

    @Test
    void aReturnEndsTheApprovalAndTheNextSubmissionStartsANewOneFromTheStart() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);
        FlowExecutionState returned = engine.returnApproval(
                nodeRequest(new FlowReturnRequest(), started.getInstanceId(), MANAGER, "fix the dates"));
        assertEquals(FlowExecutionStatus.RETURNED, returned.getStatus());
        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.RETURNED, started.getInstanceId()),
                capturedOutcome());
        assertEquals("Draft", record.get("approvalStatus"), "a returned record is a draft again");

        when(handler.submission(any())).thenReturn(new ApprovalSubmission("Leave of Alice", Map.of("days", 2)));
        FlowExecutionState submittedAgain = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        // The returned approval no longer waits for the record: this submission replaces it.
        verify(instanceService, times(2)).markSuperseded(MODEL, ROW);
        assertNotEquals(started.getInstanceId(), submittedAgain.getInstanceId());
        assertEquals(FlowExecutionStatus.WAITING, submittedAgain.getStatus());
        assertEquals("approval", submittedAgain.getPendingApprovals().getFirst().getNodeId());
        assertEquals(2, submittedAgain.getVariables().get("days"));
        verify(handler, times(2)).afterSubmitted(any());
        assertEquals("Pending", record.get("approvalStatus"));
    }

    @Test
    void aResubmissionRunsTheRevisionEffectiveNow() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);
        engine.returnApproval(nodeRequest(new FlowReturnRequest(), started.getInstanceId(), MANAGER, "make it a week"));
        publishService.publish("annual", approvalFlow("Annual leave", List.of("2002")));

        FlowExecutionState submittedAgain = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertNotEquals(started.getBundleId(), submittedAgain.getBundleId());
        assertEquals(List.of("2002"), submittedAgain.getPendingApprovals().getFirst().getApprovers());
    }

    @Test
    void withdrawalMakesTheRecordADraftAgain() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        FlowExecutionState withdrawn = launchService.withdrawRecord(MODEL, ROW, SUBMITTER, "not needed");

        assertEquals(FlowExecutionStatus.WITHDRAWN, withdrawn.getStatus());
        assertEquals(new ApprovalOutcome(ApprovalOutcomeType.WITHDRAWN, started.getInstanceId()),
                capturedOutcome());
        assertEquals("Draft", record.get("approvalStatus"));
    }

    @Test
    void aRecordWithNoApprovalInProgressCannotBeWithdrawn() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> launchService.withdrawRecord(MODEL, ROW, SUBMITTER, "old request"));

        assertTrue(error.getMessage().contains("no approval in progress"), error.getMessage());
        verify(handler, never()).afterOutcome(any(), any());
    }

    @Test
    void afterAFailedApprovalTheSubmitterTakesTheRecordBack() {
        FlowExecutionState failed = FlowExecutionState.builder()
                .instanceId("failed-1").initiatorId(SUBMITTER).recordApproval(true)
                .modelName(MODEL).rowId(ROW).status(FlowExecutionStatus.FAILED)
                .build();
        instanceStore.save(failed);
        record.put("approvalStatus", "Pending");

        assertThrows(FlowAuthorizationException.class,
                () -> launchService.withdrawRecord(MODEL, ROW, MANAGER, "not mine"));
        FlowExecutionState state = launchService.withdrawRecord(MODEL, ROW, SUBMITTER, "try again later");

        assertEquals(FlowExecutionStatus.FAILED, state.getStatus(), "the failed instance stays as it is");
        verify(handler).afterOutcome(any(), eq(new ApprovalOutcome(ApprovalOutcomeType.WITHDRAWN, "failed-1")));
        assertEquals("Draft", record.get("approvalStatus"));
    }

    @Test
    void aFlowWithoutApprovalStepsCompletesAfterTheRecordIsInApproval() {
        publishService.publish("auto", flow("Auto approve", new SubmitTrigger(MODEL), null));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
        InOrder order = inOrder(handler);
        order.verify(handler).afterSubmitted(any());
        order.verify(handler).afterOutcome(argThat(leave -> leave.getApprovalStatus() == ApprovalStatus.APPROVED),
                argThat(o -> o.type() == ApprovalOutcomeType.APPROVED));
        assertEquals("Approved", record.get("approvalStatus"));
    }

    @Test
    void aStepWhoseOnlyApproverIsTheSubmitterIsApprovedOnRecord() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(SUBMITTER)));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
        assertTrue(state.getApprovalAuditDelta().stream().anyMatch(entry ->
                entry.getAction() == ApprovalActionType.AUTO_APPROVE && SUBMITTER.equals(entry.getActorId())));
        verify(handler).afterOutcome(any(), eq(new ApprovalOutcome(ApprovalOutcomeType.APPROVED, state.getInstanceId())));
    }

    @Test
    void theSubmittersApprovalPassesAnAnyOneStepTheyShareWithOthers() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(SUBMITTER, MANAGER)));

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.COMPLETED, state.getStatus());
    }

    @Test
    void anAllApproveStepTheSubmitterSharesStillWaitsForTheOthers() {
        DesignFlowDefinition design = approvalFlow("Annual leave", List.of(SUBMITTER, MANAGER));
        design.getGraph().getNodes().set(1, FlowGraphNode.builder()
                .id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of(SUBMITTER, MANAGER))), "approvalMode", "Unanimous"))
                .build());
        publishService.publish("annual", design);

        FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

        assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
        assertEquals(List.of(SUBMITTER), state.getPendingApprovals().getFirst().getApprovedActors());
    }

    @Test
    void aStepThatResolvesToNobodyFailsTheSubmission() {
        DesignFlowDefinition design = approvalFlow("Supervisor approval", List.of(MANAGER));
        // No organization service here, so the supervisor source resolves to nobody (strategy Error).
        design.getGraph().getNodes().set(1, FlowGraphNode.builder()
                .id("approval").type(FlowNodeType.APPROVAL).label("Supervisor")
                .config(Map.of("approverSources", List.of(Map.of("type", "Supervisor", "level", 1))))
                .build());
        publishService.publish("supervisor", design);

        // Thrown from the start, so the submission's transaction rolls the record back.
        assertThrows(FlowRuntimeException.class, () -> launchService.submitRecord(MODEL, ROW, SUBMITTER));
        verify(handler, never()).afterOutcome(any(), any());
    }

    @Test
    void aFinishedApprovalTakesNoMoreComments() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);
        engine.approve(nodeRequest(new FlowApproveRequest(), started.getInstanceId(), MANAGER, null));

        FlowCommentRequest comment = new FlowCommentRequest();
        comment.setInstanceId(started.getInstanceId());
        comment.setActorId(SUBMITTER);
        comment.setComment("thanks");
        assertThrows(FlowActionValidationException.class, () -> engine.addComment(comment));

        verify(handler, times(1)).afterOutcome(any(), any());
    }

    @Test
    void aResultReachesOnlyARecordStillInApproval() {
        publishService.publish("annual", approvalFlow("Annual leave", List.of(MANAGER)));
        FlowExecutionState started = launchService.submitRecord(MODEL, ROW, SUBMITTER);
        // Moved on by a system write while its approval waited.
        record.put("approvalStatus", "Cancelled");

        engine.approve(nodeRequest(new FlowApproveRequest(), started.getInstanceId(), MANAGER, "fine"));

        assertEquals("Cancelled", record.get("approvalStatus"));
        verify(handler, never()).afterOutcome(any(), any());
    }

    private ApprovalOutcome capturedOutcome() {
        ArgumentCaptor<ApprovalOutcome> outcome = ArgumentCaptor.forClass(ApprovalOutcome.class);
        verify(handler, atLeastOnce()).afterOutcome(any(), outcome.capture());
        return outcome.getValue();
    }

    private static <T extends AbstractFlowNodeRequest> T nodeRequest(T request, String instanceId, String actorId,
                                                                    String comment) {
        request.setInstanceId(instanceId);
        request.setNodeId("approval");
        request.setActorId(actorId);
        request.setComment(comment);
        return request;
    }

    private static DesignFlowDefinition approvalFlow(String name, List<String> approvers) {
        return flow(name, new SubmitTrigger(MODEL), approvers);
    }

    private static DesignFlowDefinition withCc(DesignFlowDefinition design, FlowCcRule... rules) {
        design.setCc(List.of(rules));
        return design;
    }

    private static FlowCcRule ccRule(CcTiming timing, String recipient) {
        return FlowCcRule.builder().timing(timing)
                .recipientSource(Map.of("type", "Users", "userIds", List.of(recipient))).build();
    }

    /** start → approval → end, or start → end when {@code approvers} is null. */
    private static DesignFlowDefinition flow(String name, TriggerSource trigger, List<String> approvers) {
        List<FlowGraphNode> nodes = new ArrayList<>();
        List<FlowGraphEdge> edges = new ArrayList<>();
        nodes.add(node("start", FlowNodeType.START));
        if (approvers == null) {
            edges.add(edge("e1", "start", "end"));
        } else {
            nodes.add(FlowGraphNode.builder()
                    .id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                    .config(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", approvers))))
                    .build());
            edges.add(edge("e1", "start", "approval"));
            edges.add(edge("e2", "approval", "end"));
        }
        nodes.add(node("end", FlowNodeType.END));
        boolean approval = trigger instanceof SubmitTrigger;
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(approval ? FlowScenario.APPROVAL : FlowScenario.AUTOMATION)
                .withdrawPolicy(approval ? WithdrawPolicy.UNTIL_COMPLETED : null)
                .returnEnabled(approval ? Boolean.TRUE : null)
                .trigger(trigger)
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }

    /**
     * A submit condition and an edge may read through the document's relations, up to two on. The submission reads
     * those values from the related record, chooses the flow on them, and keeps them with the record's own values,
     * so every route of the instance decides on what they were at submission.
     */
    @Test
    void aSubmissionChoosesAndRoutesOnWhatItsRelatedRecordSaysAtSubmission() {
        try (MockedStatic<ModelManager> metadata = RecordModelFixture.mockModelManager()) {
            record.put("departmentId", 30L);
            when(modelService.searchList(eq(RecordModelFixture.RELATED), any(FlexQuery.class)))
                    .thenReturn(List.of(Map.of("costCenter", "CC1", "level", 4)));
            publishService.publish("sales", routedFlow("Sales leave", "CC1"));
            publishService.publish("others", routedFlow("Other leave", "CC2"));

            FlowExecutionState state = launchService.submitRecord(MODEL, ROW, SUBMITTER);

            assertEquals("sales", state.getFlowId());
            assertEquals("CC1", state.getVariables().get("departmentId.costCenter"));
            assertEquals(4, state.getVariables().get("departmentId.level"));
            // The edge reading departmentId.level >= 4 took the approval step, not the way straight to the end.
            assertEquals(FlowExecutionStatus.WAITING, state.getStatus());
            assertEquals(List.of(MANAGER), state.getPendingApprovals().getFirst().getApprovers());
        }
    }

    /** For departments of {@code costCenter}: start → approval when the department is level 4 or above, else → end. */
    private static DesignFlowDefinition routedFlow(String name, String costCenter) {
        DesignFlowDefinition design = flow(name, new SubmitTrigger(MODEL, null,
                Filters.of(List.of("departmentId.costCenter", "=", costCenter))), List.of(MANAGER));
        List<FlowGraphEdge> edges = design.getGraph().getEdges();
        edges.set(0, FlowGraphEdge.builder().id("e1").source("start").target("approval")
                .condition(Filters.of(List.of("departmentId.level", ">=", 4))).build());
        edges.add(edge("e3", "start", "end"));
        return design;
    }
}

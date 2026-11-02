package io.softa.starter.flow.service;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.controller.FlowInstanceController;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.dto.FlowInstanceRecordView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;
import io.softa.starter.flow.service.impl.FlowInstanceServiceImpl;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;
import io.softa.starter.flow.service.support.MetadataFieldDefinitions;
import io.softa.starter.metadata.controller.dto.MetaFieldDTO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A record's approval history and the record as its approvers see it: only the record's approval
 * flows count, users are named, CC reads and hidden fields stay out — on a one-to-many's rows too —
 * the fields a step grants past the viewer's metadata come with their definitions, and the history
 * is shown to readers of the record and to whoever took part. Read by the record, whichever approval is
 * current, it is lent by the newest approval the viewer took part in, else read with their own permissions.
 * The approver a termination cleared took part by their task; the administrator who ended it did not.
 */
class FlowRecordApprovalsTest {

    private static final String MODEL = "ExpenseClaim";
    private static final String ROW = "7";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalActionLedger ledger = mock(ApprovalActionLedger.class);
    private final OrganizationService organization = mock(OrganizationService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final FlowInstanceServiceImpl service = spy(new FlowInstanceServiceImpl());
    private final List<FlowInstance> instances = new ArrayList<>();
    private MockedStatic<ModelManager> modelManager;
    private MockedStatic<IdUtils> idUtils;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OrganizationService> organizationProvider = mock(ObjectProvider.class);
        when(organizationProvider.getIfAvailable()).thenReturn(organization);
        ReflectionTestUtils.setField(service, "bundleRegistry", bundleRegistry);
        ReflectionTestUtils.setField(service, "approvalLedger", ledger);
        ReflectionTestUtils.setField(service, "userNames", new FlowUserNames(organizationProvider, modelService));
        ReflectionTestUtils.setField(service, "modelService", modelService);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        ReflectionTestUtils.setField(service, "fieldDefinitions", new MetadataFieldDefinitions());
        doReturn(instances).when(service).findByModelNameAndRowId(MODEL, ROW);
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(MODEL)).thenReturn(true);
        idUtils = mockStatic(IdUtils.class);
        idUtils.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
        idUtils.close();
    }

    @Test
    void historyListsTheRecordsApprovalInstancesNewestFirstWithNamedUsers() {
        CompiledFlowDefinition approval = publishService.publish("claims", flow("Claims", Map.of()));
        CompiledFlowDefinition automation = publishService.publish("audit", automationFlow("Audit"));
        instance("old", approval, true, FlowExecutionStatus.REJECTED, LocalDateTime.of(2026, 10, 1, 9, 0), null);
        PendingApproval waiting = PendingApproval.builder().nodeId("approval").nodeLabel("Manager")
                .approvers(List.of("2001", "2002")).approvedActors(new ArrayList<>(List.of("2001")))
                .rejectedActors(new ArrayList<>()).build();
        instance("new", approval, true, FlowExecutionStatus.WAITING, LocalDateTime.of(2026, 10, 20, 9, 0), waiting);
        instance("other", automation, false, FlowExecutionStatus.WAITING, LocalDateTime.of(2026, 10, 21, 9, 0), null);
        // A debug run of the approval flow on the record: not its approval either.
        instance("debug", approval, false, FlowExecutionStatus.WAITING, LocalDateTime.of(2026, 10, 22, 9, 0), null);
        when(ledger.findByInstanceId("new")).thenReturn(List.of(
                entry(ApprovalActionType.APPROVE, "2001", null, "ok"),
                entry(ApprovalActionType.READ, "3001", null, null),
                entry(ApprovalActionType.TRANSFER, "2001", "2003", "please take over")));
        when(ledger.findByInstanceId("old")).thenReturn(List.of(entry(ApprovalActionType.REJECT, "2001", null, "no")));
        when(organization.getUsersByIds(anyList())).thenReturn(List.of(
                new ApproverInfo(1001L, "Alice"), new ApproverInfo(2001L, "Bob"), new ApproverInfo(2002L, "Carol")));

        FlowRecordApprovalsView history = service.recordApprovals(MODEL, ROW, view -> {});

        assertEquals(List.of("new", "old"),
                history.getInstances().stream().map(FlowRecordApprovalsView.Instance::getInstanceId).toList());
        FlowRecordApprovalsView.Instance latest = history.getInstances().getFirst();
        assertEquals("Claims", latest.getFlowName());
        assertEquals(new FlowRecordApprovalsView.Actor("1001", "Alice"), latest.getInitiator());
        assertEquals(List.of(new FlowRecordApprovalsView.Actor("2002", "Carol")),
                latest.getPending().getFirst().getApprovers());
        assertEquals(List.of(ApprovalActionType.APPROVE, ApprovalActionType.TRANSFER),
                latest.getActions().stream().map(FlowRecordApprovalsView.Action::getAction).toList());
        assertEquals(new FlowRecordApprovalsView.Actor("2003", null), latest.getActions().get(1).getTarget());
        assertTrue(history.getInstances().get(1).getPending().isEmpty());
    }

    @Test
    void aFailedApprovalSaysWhereAndWhyItStopped() {
        CompiledFlowDefinition approval = publishService.publish("claims", flow("Claims", Map.of()));
        instance("failed", approval, true, FlowExecutionStatus.FAILED, LocalDateTime.of(2026, 10, 20, 9, 0), null);
        instances.getLast().setFailedNodeId("approval");
        instances.getLast().setErrorMessage("Approval node 'approval' resolved an empty approver list (strategy=ERROR)");
        instance("old", approval, true, FlowExecutionStatus.REJECTED, LocalDateTime.of(2026, 10, 1, 9, 0), null);

        FlowRecordApprovalsView history = service.recordApprovals(MODEL, ROW, view -> {});

        assertEquals(new FlowRecordApprovalsView.Failure("approval", "Manager",
                        "Approval node 'approval' resolved an empty approver list (strategy=ERROR)"),
                history.getInstances().getFirst().getFailure());
        assertNull(history.getInstances().get(1).getFailure());
    }

    @Test
    void usersAreNamedOnlyOnceTheCallerMaySeeTheHistory() {
        CompiledFlowDefinition approval = publishService.publish("claims", flow("Claims", Map.of()));
        instance("i1", approval, true, FlowExecutionStatus.REJECTED, LocalDateTime.of(2026, 10, 1, 9, 0), null);

        assertThrows(FlowAuthorizationException.class, () -> service.recordApprovals(MODEL, ROW, view -> {
            throw new FlowAuthorizationException("no");
        }));
        verify(organization, never()).getUsersByIds(anyList());
    }

    @Test
    void theWaitingStepsRulesAreLaidOverEveryField() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of("bankAccount", "Hidden", "amount", "Editable")));
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenReturn(Optional.of(
                new LinkedHashMap<>(Map.of("id", 7L, "amount", 120, "bankAccount", "DE89 3704", "note", "lunch"))));

        Map<String, Object> record = service.recordOf(waitingAt(approval), APPROVER).orElseThrow().record();

        // Hidden is left out; a field the step does not list is still shown.
        assertEquals(Map.of("id", 7L, "amount", 120, "note", "lunch"), record);
    }

    @Test
    void aStepThatListsNoFieldsShowsThemAll() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of()));
        Map<String, Object> stored = Map.of("id", 7L, "amount", 120, "note", "lunch");
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE)))
                .thenReturn(Optional.of(new LinkedHashMap<>(stored)));

        assertEquals(stored, service.recordOf(waitingAt(approval), APPROVER).orElseThrow().record());
    }

    @Test
    void aSensitiveFieldTheViewerMaySeeNowhereIsShownOnlyWhereTheStepListsIt() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of("salary", "Readonly")));
        when(permissions.getHiddenSensitiveFields()).thenReturn(Map.of(MODEL, Set.of("salary", "bankAccount")));
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenReturn(Optional.of(
                new LinkedHashMap<>(Map.of("id", 7L, "salary", 9000, "bankAccount", "DE89 3704", "note", "raise"))));

        assertEquals(Map.of("id", 7L, "salary", 9000, "note", "raise"),
                service.recordOf(waitingAt(approval), APPROVER).orElseThrow().record());
        // Someone with no step there — a CC recipient — sees neither.
        assertEquals(Map.of("id", 7L, "note", "raise"),
                service.recordOf(waitingAt(approval), "3001").orElseThrow().record());
    }

    @Test
    void whatAStepHidesStaysHiddenFromOthersButNotFromTheInitiator() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of("bankAccount", "Hidden")));
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenAnswer(invocation ->
                Optional.of(new LinkedHashMap<>(Map.of("id", 7L, "bankAccount", "DE89 3704", "note", "lunch"))));

        assertEquals(Map.of("id", 7L, "note", "lunch"),
                service.recordOf(waitingAt(approval), "3001").orElseThrow().record());
        assertEquals(Map.of("id", 7L, "bankAccount", "DE89 3704", "note", "lunch"),
                service.recordOf(waitingAt(approval), INITIATOR).orElseThrow().record());
    }

    @Test
    void aSensitiveFieldAStepGrantsComesWithItsDefinition() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of("salary", "Readonly")));
        when(permissions.getHiddenSensitiveFields()).thenReturn(Map.of(MODEL, Set.of("salary", "bankAccount")));
        definedField(MODEL, "salary", FieldType.BIG_DECIMAL);
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenAnswer(invocation ->
                Optional.of(new LinkedHashMap<>(Map.of("id", 7L, "salary", 9000, "bankAccount", "DE89 3704"))));

        // The viewer's metadata leaves the field out, so the record says what it is.
        assertEquals(Map.of(MODEL, List.of("salary")),
                definedFields(service.recordOf(waitingAt(approval), APPROVER).orElseThrow()));
        // Nothing withheld is shown to the initiator: nothing to define.
        assertEquals(Map.of(), service.recordOf(waitingAt(approval), INITIATOR).orElseThrow().fields());
    }

    @Test
    void aChildRowLosesTheSensitiveFieldsTheViewerMaySeeNowhere() {
        CompiledFlowDefinition approval = publishService.publish("claims", flow("Claims", Map.of()));
        claimWithLines();

        FlowInstanceRecordView view = service.recordOf(waitingAt(approval), APPROVER).orElseThrow();

        // The step does not list the lines: their rows follow the rule the record's own fields follow.
        assertEquals(List.of(Map.of("id", 71L, "item", "Base")), view.record().get("lines"));
        assertEquals(Map.of(), view.fields());
    }

    @Test
    void aStepThatListsTheOneToManyShowsItsRowsWholeWithTheirDefinitions() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of("lines", "Readonly")));
        claimWithLines();

        FlowInstanceRecordView view = service.recordOf(waitingAt(approval), APPROVER).orElseThrow();

        assertEquals(List.of(line(71L, "Base", 5000, 5500)), view.record().get("lines"));
        assertEquals(Map.of(LINE_MODEL, List.of("newAmount", "oldAmount")), definedFields(view));
        // The step grants its approver alone: the initiator and anyone else see the rows as their roles allow.
        assertEquals(List.of(Map.of("id", 71L, "item", "Base")),
                service.recordOf(waitingAt(approval), INITIATOR).orElseThrow().record().get("lines"));
        assertEquals(List.of(Map.of("id", 71L, "item", "Base")),
                service.recordOf(waitingAt(approval), "3001").orElseThrow().record().get("lines"));
    }

    @Test
    void aStepThatHidesTheOneToManyLeavesItOut() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of("lines", "Hidden")));
        claimWithLines();

        FlowInstanceRecordView view = service.recordOf(waitingAt(approval), APPROVER).orElseThrow();

        assertEquals(Map.of("id", 7L), view.record());
        assertEquals(Map.of(), view.fields());
    }

    @Test
    void aReaderOfTheRecordGetsTheModelsOwnRead() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of("lines", "Readonly")));
        claimWithLines();
        List<Boolean> skipped = new ArrayList<>();
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return Optional.of(new LinkedHashMap<>(Map.of("id", 7L, "lines", List.of(line(71L, "Base", 5000, 5500)))));
        });

        FlowInstanceRecordView view = service.recordThroughOwnPermissions(MODEL, ROW).orElseThrow();
        service.recordOf(waitingAt(approval), APPROVER).orElseThrow();

        // Read with the reader's own permissions — whatever their roles withhold, the read itself leaves out or
        // masks — where a grantee's read sets them aside.
        assertEquals(List.of(false, true), skipped);
        assertEquals(List.of(line(71L, "Base", 5000, 5500)), view.record().get("lines"));
        assertEquals(Map.of(), view.fields());
    }

    private static final String LINE_MODEL = "ExpenseLine";

    /** A claim with one line, whose old and new amounts the viewer's roles withhold. */
    private void claimWithLines() {
        MetaField lines = field("lines", FieldType.ONE_TO_MANY);
        when(lines.getRelatedModel()).thenReturn(LINE_MODEL);
        modelManager.when(() -> ModelManager.getModelFields(MODEL)).thenReturn(List.of(lines));
        when(permissions.getHiddenSensitiveFields())
                .thenReturn(Map.of(LINE_MODEL, new TreeSet<>(Set.of("oldAmount", "newAmount"))));
        definedField(LINE_MODEL, "oldAmount", FieldType.BIG_DECIMAL);
        definedField(LINE_MODEL, "newAmount", FieldType.BIG_DECIMAL);
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenAnswer(invocation ->
                Optional.of(new LinkedHashMap<>(Map.of("id", 7L, "lines", List.of(line(71L, "Base", 5000, 5500))))));
    }

    private static Map<String, Object> line(long id, String item, int oldAmount, int newAmount) {
        return new LinkedHashMap<>(Map.of("id", id, "item", item, "oldAmount", oldAmount, "newAmount", newAmount));
    }

    private void definedField(String model, String name, FieldType type) {
        MetaField field = field(name, type);
        when(field.getModelName()).thenReturn(model);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(model, name)).thenReturn(field);
    }

    /** The fields a view defines, by model — each definition as the metadata API serves it. */
    private static Map<String, List<String>> definedFields(FlowInstanceRecordView view) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        view.fields().forEach((model, definitions) -> fields.put(model, definitions.stream()
                .map(definition -> {
                    MetaFieldDTO field = (MetaFieldDTO) definition;
                    assertEquals(model, field.getModelName());
                    return field.getFieldName();
                })
                .toList()));
        return fields;
    }

    private static final String APPROVER = "2001";
    private static final String INITIATOR = "1001";

    private FlowExecutionState waitingAt(CompiledFlowDefinition approval) {
        return FlowExecutionState.builder()
                .instanceId("i1").bundleId(approval.getBundleId()).modelName(MODEL).rowId(ROW)
                .status(FlowExecutionStatus.WAITING).initiatorId(INITIATOR)
                .pendingApprovals(List.of(PendingApproval.builder().nodeId("approval").approvers(List.of(APPROVER)).build()))
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRecordComesWithItsChildRowsInFullAndItsManyToManyAsReferences() {
        CompiledFlowDefinition approval = publishService.publish("claims",
                flow("Claims", Map.of()));
        MetaField currency = field("companyCurrency", FieldType.MANY_TO_ONE);
        when(currency.isDynamic()).thenReturn(true);
        List<MetaField> modelFields = List.of(field("amount", FieldType.BIG_DECIMAL),
                field("lines", FieldType.ONE_TO_MANY), field("tags", FieldType.MANY_TO_MANY), currency);
        modelManager.when(() -> ModelManager.getModelStoredFields(MODEL)).thenReturn(List.of("id", "amount"));
        modelManager.when(() -> ModelManager.getModelFields(MODEL)).thenReturn(modelFields);
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE)))
                .thenReturn(Optional.of(Map.of("id", 7L)));
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("i1").bundleId(approval.getBundleId()).modelName(MODEL).rowId(ROW)
                .status(FlowExecutionStatus.COMPLETED).build();

        service.recordOf(state, INITIATOR).orElseThrow();

        ArgumentCaptor<List<String>> fields = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<SubQueries> subQueries = ArgumentCaptor.forClass(SubQueries.class);
        verify(modelService).getById(eq(MODEL), any(), fields.capture(), subQueries.capture(), eq(ConvertType.REFERENCE));
        assertEquals(List.of("id", "amount", "lines", "tags", "companyCurrency"), fields.getValue());
        assertTrue(subQueries.getValue().contains("lines"));
        assertFalse(subQueries.getValue().contains("tags"));
    }

    private static MetaField field(String name, FieldType type) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldName()).thenReturn(name);
        when(field.getFieldType()).thenReturn(type);
        return field;
    }

    @Test
    void theHistoryIsShownToParticipantsAndReadersOnly() {
        FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(service,
                new ApprovalAuditReader(new NoopApprovalActionLedger()), modelService);
        FlowRecordApprovalsView history = new FlowRecordApprovalsView(List.of(FlowRecordApprovalsView.Instance.builder()
                .instanceId("i1")
                .initiator(new FlowRecordApprovalsView.Actor("1001", "Alice"))
                .pending(List.of())
                .actions(List.of(FlowRecordApprovalsView.Action.builder()
                        .action(ApprovalActionType.APPROVE).actor(new FlowRecordApprovalsView.Actor("2001", "Bob")).build()))
                .build()));
        when(modelService.getById(eq(MODEL), any(), anyList())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> guard.requireRecordViewer(MODEL, ROW, history, "1001"));
        assertDoesNotThrow(() -> guard.requireRecordViewer(MODEL, ROW, history, "2001"));
        assertThrows(FlowAuthorizationException.class, () -> guard.requireRecordViewer(MODEL, ROW, history, "9999"));

        when(modelService.getById(eq(MODEL), any(), anyList())).thenReturn(Optional.of(Map.of("id", 7L)));
        assertDoesNotThrow(() -> guard.requireRecordViewer(MODEL, ROW, history, "9999"));
    }

    @Test
    void theRecordsApprovalsComeAsTheEngineLoadsThemNewestFirst() {
        CompiledFlowDefinition approval = publishService.publish("claims", flow("Claims", Map.of()));
        CompiledFlowDefinition automation = publishService.publish("audit", automationFlow("Audit"));
        instance("old", approval, true, FlowExecutionStatus.RETURNED, LocalDateTime.of(2026, 10, 1, 9, 0), null);
        instance("new", approval, true, FlowExecutionStatus.WAITING, LocalDateTime.of(2026, 10, 20, 9, 0),
                PendingApproval.builder().nodeId("approval").approvers(List.of("2002")).build());
        instance("other", automation, false, FlowExecutionStatus.WAITING, LocalDateTime.of(2026, 10, 21, 9, 0), null);

        List<FlowExecutionState> approvals = service.recordApprovalStates(MODEL, ROW);

        // The instances the history lists, each with what deciding who takes part in it reads.
        assertEquals(List.of("new", "old"), approvals.stream().map(FlowExecutionState::getInstanceId).toList());
        assertTrue(approvals.stream().allMatch(FlowExecutionState::isApprovalOfRecord));
        assertEquals(List.of("2002"), approvals.getFirst().getPendingApprovals().getFirst().getApprovers());
        assertEquals("1001", approvals.getLast().getInitiatorId());
    }

    // ---- the record read through its approvals, whichever is current: GET /flow/instances/by-record/record ----

    private static final String EARLIER_APPROVER = "2001";
    private static final String CURRENT_APPROVER = "2002";
    private static final String READER = "3001";
    private static final String OUTSIDER = "9999";
    private static final String ADMINISTRATOR = "8001";

    @Test
    void aCurrentApproverReadsTheRecordAsTheirWaitingStepShowsIt() {
        resubmittedClaim();

        FlowInstanceRecordView view = as(CURRENT_APPROVER, () -> recordPage().getRecordThroughApprovals(MODEL, ROW)).getData();

        // Their step shows the salary their roles withhold, and says what it is; revision 2 hides no bank account.
        assertEquals(Map.of("id", 7L, "salary", 9000, "bankAccount", "DE89 3704", "note", "raise"), view.record());
        assertEquals(Map.of(MODEL, List.of("salary")), definedFields(view));
    }

    @Test
    void anEarlierApproverReadsItAsTheirOwnApprovalShowsItToSomeoneWithNoWaitingStep() {
        resubmittedClaim();

        FlowInstanceRecordView view = as(EARLIER_APPROVER, () -> recordPage().getRecordThroughApprovals(MODEL, ROW)).getData();

        // Through the first approval, which waits on nobody: no salary, and no bank account — revision 1 hides it,
        // where the approval in progress would show it.
        assertEquals(Map.of("id", 7L, "note", "raise"), view.record());
        assertEquals(Map.of(), view.fields());
    }

    @Test
    void theInitiatorReadsItPastTheirOwnPermissions() {
        resubmittedClaim();

        FlowInstanceRecordView view = as(INITIATOR, () -> recordPage().getRecordThroughApprovals(MODEL, ROW)).getData();

        // Every field but what their roles withhold, though their own permissions do not reach the record.
        assertEquals(Map.of("id", 7L, "bankAccount", "DE89 3704", "note", "raise"), view.record());
        assertEquals(Map.of(), view.fields());
        verify(modelService, never()).getById(eq(MODEL), any(), anyList());
    }

    @Test
    void aReaderWhoTookPartInNoneGetsTheModelsOwnReadWithNoFields() {
        resubmittedClaim();
        when(modelService.getById(eq(MODEL), any(), anyList())).thenReturn(Optional.of(Map.of("id", 7L)));
        List<Boolean> skipped = new ArrayList<>();
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenAnswer(invocation -> {
            skipped.add(ContextHolder.getContext().isSkipPermissionCheck());
            return Optional.of(new LinkedHashMap<>(Map.of("id", 7L, "bankAccount", "DE89 3704", "note", "raise")));
        });

        FlowInstanceRecordView view = as(READER, () -> recordPage().getRecordThroughApprovals(MODEL, ROW)).getData();

        // Read with their own permissions: whatever their roles withhold, the model's read itself leaves out.
        assertEquals(Map.of("id", 7L, "bankAccount", "DE89 3704", "note", "raise"), view.record());
        assertEquals(Map.of(), view.fields());
        assertEquals(List.of(false), skipped);
    }

    @Test
    void aRecordNeverSubmittedIsReadByWhoeverCanReadIt() {
        // No approval at all: a draft its owner opens.
        when(modelService.getById(eq(MODEL), any(), anyList())).thenReturn(Optional.of(Map.of("id", 7L)));
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE)))
                .thenReturn(Optional.of(new LinkedHashMap<>(Map.of("id", 7L, "note", "lunch"))));

        FlowInstanceRecordView view = as(INITIATOR, () -> recordPage().getRecordThroughApprovals(MODEL, ROW)).getData();

        assertEquals(Map.of("id", 7L, "note", "lunch"), view.record());
        assertEquals(Map.of(), view.fields());
    }

    @Test
    void whoTookPartInNoneAndCannotReadTheRecordIsRefused() {
        resubmittedClaim();

        assertThrows(FlowAuthorizationException.class,
                () -> as(OUTSIDER, () -> recordPage().getRecordThroughApprovals(MODEL, ROW)));
        verify(modelService, never()).getById(eq(MODEL), any(), anyList(), any(), any());
    }

    @Test
    void anUnknownModelOrARecordGoneFromUnderItsApprovalsIsNotFound() {
        resubmittedClaim();
        FlowInstanceController page = recordPage();

        // A model that does not exist: not found, whoever asks.
        assertEquals(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                as(OUTSIDER, () -> page.getRecordThroughApprovals("NoSuchModel", ROW)).getCode());
        // The record deleted: not found to whoever took part in its approvals...
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenReturn(Optional.empty());
        assertEquals(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                as(CURRENT_APPROVER, () -> page.getRecordThroughApprovals(MODEL, ROW)).getCode());
        assertEquals(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                as(EARLIER_APPROVER, () -> page.getRecordThroughApprovals(MODEL, ROW)).getCode());
        // ...and refused to anyone else, as a record they cannot read: whether it exists is not theirs to learn.
        assertThrows(FlowAuthorizationException.class, () -> as(OUTSIDER, () -> page.getRecordThroughApprovals(MODEL, ROW)));
    }

    @Test
    void aTerminatedApprovalsClearedApproverSeesTheRecordsApprovalsAndTheAdministratorWhoEndedItDoesNot() {
        terminatedResubmission();
        // The approver it waited on never acted: only their task, which the termination closed, names them.
        when(modelService.count(eq("FlowApprovalTask"), eq(tasksOf(CURRENT_APPROVER, "second", "first"))))
                .thenReturn(1L);
        when(modelService.searchList(eq("FlowApprovalTask"), argThat(query -> query != null
                && tasksOf(CURRENT_APPROVER, "second", "first").equals(query.getFilters()))))
                .thenReturn(List.of(Map.of("instanceId", "second")));
        FlowInstanceController page = recordPage();

        FlowRecordApprovalsView history = as(CURRENT_APPROVER, () -> page.getRecordApprovals(MODEL, ROW)).getData();
        FlowInstanceRecordView view = as(CURRENT_APPROVER, () -> page.getRecordThroughApprovals(MODEL, ROW)).getData();

        assertEquals(List.of("second", "first"),
                history.getInstances().stream().map(FlowRecordApprovalsView.Instance::getInstanceId).toList());
        // Lent by the terminated approval, which waits on nobody now: no salary, and revision 2 hides no bank account.
        assertEquals(Map.of("id", 7L, "bankAccount", "DE89 3704", "note", "raise"), view.record());
        // The administrator who urged and terminated it holds no task and cannot read the record: refused both, as
        // anyone else is.
        for (String refused : List.of(ADMINISTRATOR, OUTSIDER)) {
            assertThrows(FlowAuthorizationException.class, () -> as(refused, () -> page.getRecordApprovals(MODEL, ROW)));
            assertThrows(FlowAuthorizationException.class,
                    () -> as(refused, () -> page.getRecordThroughApprovals(MODEL, ROW)));
        }
    }

    /**
     * {@link #resubmittedClaim()}, its second approval then urged and terminated by an administrator while it waited
     * on its approver: the step is cleared, and the approver, who never acted, is named by no history.
     */
    private void terminatedResubmission() {
        resubmittedClaim();
        FlowInstance second = instances.getLast();
        second.setStatus(FlowExecutionStatus.CANCELLED);
        second.setPendingApprovals(JsonUtils.objectToString(List.of()));
        when(ledger.findByInstanceId("second")).thenReturn(List.of(
                entry(ApprovalActionType.URGE, ADMINISTRATOR, null, "please act on it"),
                entry(ApprovalActionType.TERMINATE, ADMINISTRATOR, null, "raised twice")));
    }

    /** A user's tasks on these approvals, as the access guard asks for them: whatever their type and status. */
    private static Filters tasksOf(String userId, String... instanceIds) {
        return new Filters()
                .in(FlowApprovalTask::getInstanceId, List.of(instanceIds))
                .eq(FlowApprovalTask::getActorId, userId);
    }

    /**
     * A claim its initiator submitted twice. The first approval ran revision 1, which hides the bank account, and its
     * approver returned it; the second runs revision 2, whose step shows the salary — withheld by every viewer's
     * roles — and waits on another approver.
     */
    private void resubmittedClaim() {
        CompiledFlowDefinition first = publishService.publish("claims", flow("Claims", Map.of("bankAccount", "Hidden")));
        CompiledFlowDefinition second = publishService.publish("claims", flow("Claims", Map.of("salary", "Readonly")));
        instance("first", first, true, FlowExecutionStatus.RETURNED, LocalDateTime.of(2026, 10, 1, 9, 0), null);
        instance("second", second, true, FlowExecutionStatus.WAITING, LocalDateTime.of(2026, 10, 20, 9, 0),
                PendingApproval.builder().nodeId("approval").nodeLabel("Manager")
                        .approvers(List.of(CURRENT_APPROVER)).build());
        when(ledger.findByInstanceId("first"))
                .thenReturn(List.of(entry(ApprovalActionType.RETURN, EARLIER_APPROVER, null, "fix the amount")));
        when(permissions.getHiddenSensitiveFields()).thenReturn(Map.of(MODEL, Set.of("salary")));
        definedField(MODEL, "salary", FieldType.BIG_DECIMAL);
        when(modelService.getById(eq(MODEL), any(), anyList(), any(), eq(ConvertType.REFERENCE))).thenAnswer(invocation ->
                Optional.of(new LinkedHashMap<>(Map.of("id", 7L, "salary", 9000, "bankAccount", "DE89 3704", "note", "raise"))));
    }

    /** The record page's read, over this service and the access guard as Spring wires them. */
    private FlowInstanceController recordPage() {
        FlowInstanceAccessGuard guard = new FlowInstanceAccessGuard(service, new ApprovalAuditReader(ledger), modelService);
        return new FlowInstanceController(null, null, service, null, guard, null, null, null, null, null, null, null);
    }

    private static <T> T as(String userId, Supplier<T> call) {
        Context context = new Context();
        context.setUserId(Long.valueOf(userId));
        return ContextHolder.callWith(context, call::get);
    }

    private void instance(String instanceId, CompiledFlowDefinition flow, boolean recordApproval,
                          FlowExecutionStatus status, LocalDateTime createdTime, PendingApproval pending) {
        FlowInstance instance = new FlowInstance();
        instance.setInstanceId(instanceId);
        instance.setBundleId(flow.getBundleId());
        instance.setFlowId(flow.getFlowId());
        instance.setRecordApproval(recordApproval);
        instance.setStatus(status);
        instance.setInitiatorId("1001");
        instance.setModelName(MODEL);
        instance.setRowId(ROW);
        instance.setCreatedTime(createdTime);
        instance.setPendingApprovals(JsonUtils.objectToString(pending == null ? List.of() : List.of(pending)));
        instances.add(instance);
    }

    private static ApprovalActionAuditEntry entry(ApprovalActionType action, String actorId, String targetActorId,
                                                  String comment) {
        return ApprovalActionAuditEntry.builder()
                .action(action).actorId(actorId).targetActorId(targetActorId).comment(comment)
                .nodeLabel("Manager").build();
    }

    /** An automation flow on the record: start → end, nothing to approve. */
    private static DesignFlowDefinition automationFlow(String name) {
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(FlowGraphEdge.builder().id("e1").source("start").target("end").build()))
                        .build())
                .build();
    }

    private static DesignFlowDefinition flow(String name, Map<String, String> formPermissions) {
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger(MODEL))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                                        .config(Map.of("approvers", List.of("2001"), "formPermissions", formPermissions))
                                        .build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }
}

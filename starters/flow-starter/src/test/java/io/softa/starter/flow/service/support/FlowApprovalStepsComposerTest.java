package io.softa.starter.flow.service.support;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.dto.FlowApprovalPreview.AutoApprovalReason;
import io.softa.starter.flow.dto.FlowApprovalPreview.StepOutcome;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Actor;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Approver;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.ApproverResult;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Event;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Failure;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Instance;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Step;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.StepState;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;
import io.softa.starter.flow.service.FlowDelegationService;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FlowApprovalStepsComposer}: a submission's steps in the order its approval reached them — those
 * it passed and those it waits on, from its tasks and history, each approver with their result, time and comment;
 * then, while it is in progress, those still ahead with the approvers expected. Steps handed on list both approvers,
 * with whom and by whom; copies, urges and comments sit under their step; automatic approvals say why; and each
 * submission says how it ended.
 */
class FlowApprovalStepsComposerTest {

    private static final String SUBMITTER = "1001";
    private static final String MANAGER = "2001";
    private static final String HR = "2002";
    private static final String FINANCE = "2003";
    private static final String DEPUTY = "2004";
    private static final String READER = "3001";
    private static final String ADMINISTRATOR = "8001";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 2, 9, 0);

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalActionLedger ledger = mock(ApprovalActionLedger.class);
    private final FlowApprovalStepsComposer composer = composer();

    @Test
    void passedAndWaitingStepsComeFromTheTasksThenTheRouteAheadIsExpected() {
        CompiledFlowDefinition flow = publishService.publish("claims", chain("Claims",
                approval("manager", "Manager", MANAGER), approval("hr", "HR", HR), approval("finance", "Finance", FINANCE)));
        FlowInstance instance = instance(flow, FlowExecutionStatus.WAITING, waiting("hr", "HR", HR));
        List<ApprovalActionAuditEntry> history = List.of(
                entry(1, ApprovalActionType.APPROVE, "manager", MANAGER, null, null, "ok", at(2)));
        when(ledger.findByInstanceId("i1")).thenReturn(history);

        Instance submission = composer.compose(instance, history, List.of(
                task("manager", MANAGER, FlowApprovalTaskStatus.APPROVED, at(1), at(2), "ok"),
                task("hr", HR, FlowApprovalTaskStatus.PENDING, at(2), null, null)));

        assertEquals(List.of("manager", "hr", "finance"), submission.getSteps().stream().map(Step::getNodeId).toList());
        Step passed = submission.getSteps().getFirst();
        assertEquals(StepState.DONE, passed.getState());
        assertEquals("Manager", passed.getNodeLabel());
        assertEquals(VoteThresholdMode.ANY_ONE, passed.getApprovalMode());
        assertEquals(at(1), passed.getEnteredAt());
        assertEquals(at(2), passed.getFinishedAt());
        assertEquals(List.of(row(MANAGER, ApproverResult.APPROVED, at(2), "ok")), passed.getApprovers());
        Step current = submission.getSteps().get(1);
        assertEquals(StepState.CURRENT, current.getState());
        assertEquals(at(2), current.getEnteredAt());
        assertNull(current.getFinishedAt());
        assertEquals(1, current.getRequiredApprovals());
        assertEquals(List.of(row(HR, ApproverResult.PENDING, null, null)), current.getApprovers());
        Step ahead = submission.getSteps().get(2);
        assertEquals(StepState.UPCOMING, ahead.getState());
        assertEquals(StepOutcome.WAITING, ahead.getOutcome());
        assertNull(ahead.getEnteredAt());
        assertEquals(List.of(row(FINANCE, ApproverResult.EXPECTED, null, null)), ahead.getApprovers());
        assertEquals(List.of(), submission.getUndecided());
        assertNull(submission.getEnded());
        assertEquals(new Actor(SUBMITTER, null), submission.getInitiator());
        assertEquals(T0, submission.getStartedAt());
    }

    @Test
    void aStepHandedOnListsBothApproversWithWhomAndByWhom() {
        CompiledFlowDefinition flow = publishService.publish("claims", chain("Claims", approval("manager", "Manager", MANAGER)));
        FlowInstance instance = instance(flow, FlowExecutionStatus.COMPLETED, null);
        List<ApprovalActionAuditEntry> history = List.of(
                entry(1, ApprovalActionType.TRANSFER, "manager", MANAGER, DEPUTY, ADMINISTRATOR, "on leave", at(2)),
                entry(2, ApprovalActionType.APPROVE, "manager", DEPUTY, null, null, "fine", at(3)));

        Instance submission = composer.compose(instance, history, List.of(
                task("manager", MANAGER, FlowApprovalTaskStatus.TRANSFERRED, at(1), at(2), "on leave"),
                task("manager", DEPUTY, FlowApprovalTaskStatus.APPROVED, at(2), at(3), "fine")));

        Step step = submission.getSteps().getFirst();
        assertEquals(List.of(
                        Approver.builder().actor(new Actor(MANAGER, null)).result(ApproverResult.TRANSFERRED)
                                .target(new Actor(DEPUTY, null)).operator(new Actor(ADMINISTRATOR, null))
                                .time(at(2)).comment("on leave").build(),
                        row(DEPUTY, ApproverResult.APPROVED, at(3), "fine")),
                step.getApprovers());
        assertEquals(at(3), step.getFinishedAt());
        // Completed: when its history ends, by nobody in particular.
        assertNull(submission.getEnded().getAction());
        assertEquals(at(3), submission.getEnded().getTime());
    }

    @Test
    void copiesUrgesAndCommentsAreListedUnderTheirStep() {
        CompiledFlowDefinition flow = publishService.publish("claims", chain("Claims", approval("manager", "Manager", MANAGER)));
        FlowInstance instance = instance(flow, FlowExecutionStatus.WAITING, waiting("manager", "Manager", MANAGER));
        List<ApprovalActionAuditEntry> history = List.of(
                entry(1, ApprovalActionType.CC, "manager", MANAGER, READER, null, "fyi", at(2)),
                entry(2, ApprovalActionType.READ, "manager", READER, null, null, null, at(3)),
                entry(3, ApprovalActionType.URGE, "manager", SUBMITTER, null, null, null, at(4)),
                entry(4, ApprovalActionType.COMMENT, "manager", MANAGER, null, null, "looking", at(5)));
        when(ledger.findByInstanceId("i1")).thenReturn(history);
        FlowApprovalTask copy = task("manager", READER, FlowApprovalTaskStatus.READ, at(2), at(3), "fyi");
        copy.setTaskType(FlowApprovalTaskType.CC);

        Instance submission = composer.compose(instance, history,
                List.of(task("manager", MANAGER, FlowApprovalTaskStatus.PENDING, at(1), null, null), copy));

        Step step = submission.getSteps().getFirst();
        // The copy is no approver's row; reading it is no event.
        assertEquals(List.of(row(MANAGER, ApproverResult.PENDING, null, null)), step.getApprovers());
        assertEquals(List.of(
                        Event.builder().action(ApprovalActionType.CC).actor(new Actor(MANAGER, null))
                                .target(new Actor(READER, null)).comment("fyi").time(at(2)).read(true).build(),
                        Event.builder().action(ApprovalActionType.URGE).actor(new Actor(SUBMITTER, null)).time(at(4)).build(),
                        Event.builder().action(ApprovalActionType.COMMENT).actor(new Actor(MANAGER, null))
                                .comment("looking").time(at(5)).build()),
                step.getEvents());
        assertEquals(List.of(), submission.getEvents());
    }

    @Test
    void automaticApprovalsSayWhyAndAStepWithNobodyToAskSaysSo() {
        CompiledFlowDefinition flow = publishService.publish("claims", chain("Claims",
                approval("manager", "Manager", MANAGER), approval("hr", "HR", MANAGER), approval("finance", "Finance", FINANCE)));
        FlowInstance instance = instance(flow, FlowExecutionStatus.COMPLETED, null);
        List<ApprovalActionAuditEntry> history = List.of(
                entry(1, ApprovalActionType.AUTO_APPROVE, "manager", SUBMITTER, null, null, "Auto-approved (submitter)", at(1)),
                entry(2, ApprovalActionType.APPROVE, "manager", MANAGER, null, null, null, at(2)),
                entry(3, ApprovalActionType.AUTO_APPROVE, "hr", MANAGER, null, null, null, at(2)),
                entry(4, ApprovalActionType.AUTO_APPROVE, "finance", null, null, null, "Auto-approved (no approver)", at(2)));

        Instance submission = composer.compose(instance, history,
                List.of(task("manager", MANAGER, FlowApprovalTaskStatus.APPROVED, at(1), at(2), null)));

        assertEquals(List.of("manager", "hr", "finance"), submission.getSteps().stream().map(Step::getNodeId).toList());
        assertEquals(List.of(row(MANAGER, ApproverResult.APPROVED, at(2), null),
                        Approver.builder().actor(new Actor(SUBMITTER, null)).result(ApproverResult.AUTO_APPROVED)
                                .time(at(1)).autoApprovalReason(AutoApprovalReason.SUBMITTER).build()),
                submission.getSteps().getFirst().getApprovers());
        assertEquals(List.of(Approver.builder().actor(new Actor(MANAGER, null)).result(ApproverResult.AUTO_APPROVED)
                        .time(at(2)).autoApprovalReason(AutoApprovalReason.EARLIER_APPROVAL).build()),
                submission.getSteps().get(1).getApprovers());
        Step nobody = submission.getSteps().get(2);
        assertEquals(List.of(), nobody.getApprovers());
        assertEquals(StepOutcome.NO_APPROVER_APPROVED, nobody.getOutcome());
        assertEquals(StepState.DONE, nobody.getState());
    }

    @Test
    void eachSubmissionSaysHowItEnded() {
        CompiledFlowDefinition flow = publishService.publish("claims", chain("Claims", approval("manager", "Manager", MANAGER)));

        Instance rejected = composer.compose(instance(flow, FlowExecutionStatus.REJECTED, null),
                List.of(entry(1, ApprovalActionType.REJECT, "manager", MANAGER, null, null, "no", at(2))),
                List.of(task("manager", MANAGER, FlowApprovalTaskStatus.REJECTED, at(1), at(2), "no")));
        assertEquals(ApprovalActionType.REJECT, rejected.getEnded().getAction());
        assertEquals(new Actor(MANAGER, null), rejected.getEnded().getActor());
        assertEquals("no", rejected.getEnded().getComment());
        assertEquals(ApproverResult.REJECTED, rejected.getSteps().getFirst().getApprovers().getFirst().getResult());

        // Withdrawn while it waited: the approver no longer asked.
        Instance withdrawn = composer.compose(instance(flow, FlowExecutionStatus.WITHDRAWN, null),
                List.of(entry(1, ApprovalActionType.WITHDRAW, null, SUBMITTER, null, null, "wrong dates", at(2))),
                List.of(task("manager", MANAGER, FlowApprovalTaskStatus.WITHDRAWN, at(1), at(2), null)));
        assertEquals(ApprovalActionType.WITHDRAW, withdrawn.getEnded().getAction());
        assertEquals(new Actor(SUBMITTER, null), withdrawn.getEnded().getActor());
        assertEquals(at(2), withdrawn.getEnded().getTime());
        assertEquals(ApproverResult.NOT_NEEDED, withdrawn.getSteps().getFirst().getApprovers().getFirst().getResult());

        Instance terminated = composer.compose(instance(flow, FlowExecutionStatus.CANCELLED, null),
                List.of(entry(1, ApprovalActionType.TERMINATE, null, ADMINISTRATOR, null, null, "duplicate", at(2))),
                List.of(task("manager", MANAGER, FlowApprovalTaskStatus.CANCELED, at(1), at(2), null)));
        assertEquals(ApprovalActionType.TERMINATE, terminated.getEnded().getAction());
        assertEquals("duplicate", terminated.getEnded().getComment());

        FlowInstance stopped = instance(flow, FlowExecutionStatus.FAILED, null);
        stopped.setFailedNodeId("manager");
        stopped.setErrorMessage("Webhook answered 502");
        stopped.setRetryAfterNodeId("manager");
        Instance failed = composer.compose(stopped,
                List.of(entry(1, ApprovalActionType.APPROVE, "manager", MANAGER, null, null, null, at(2)),
                        entry(2, ApprovalActionType.RETRY, null, ADMINISTRATOR, null, null, "fixed the hook", at(3))),
                List.of(task("manager", MANAGER, FlowApprovalTaskStatus.APPROVED, at(1), at(2), null)));
        assertNull(failed.getEnded(), "its failure says where and why");
        assertEquals(new Failure("manager", "Manager", "Webhook answered 502", true), failed.getFailure());
        // An administrator's retry is the approval's, not a step's.
        assertEquals(List.of(ApprovalActionType.RETRY), failed.getEvents().stream().map(Event::getAction).toList());
        assertEquals(List.of(), failed.getSteps().getFirst().getEvents());
    }

    @Test
    void othersApprovingEnoughLeaveTheRestNotNeeded() {
        CompiledFlowDefinition flow = publishService.publish("claims", chain("Claims",
                approval("manager", "Manager", MANAGER, DEPUTY)));
        Instance submission = composer.compose(instance(flow, FlowExecutionStatus.COMPLETED, null),
                List.of(entry(1, ApprovalActionType.APPROVE, "manager", MANAGER, null, null, null, at(2))),
                List.of(task("manager", MANAGER, FlowApprovalTaskStatus.APPROVED, at(1), at(2), null),
                        task("manager", DEPUTY, FlowApprovalTaskStatus.CANCELED, at(1), at(2), null)));

        assertEquals(List.of(ApproverResult.APPROVED, ApproverResult.NOT_NEEDED),
                submission.getSteps().getFirst().getApprovers().stream().map(Approver::getResult).toList());
    }

    @Test
    void aSubmissionWhoseRevisionIsGoneShowsTheStepsItReachedAndNoneAhead() {
        CompiledFlowDefinition flow = publishService.publish("claims", chain("Claims",
                approval("manager", "Manager", MANAGER), approval("hr", "HR", HR)));
        FlowInstance instance = instance(flow, FlowExecutionStatus.WAITING, waiting("manager", "Manager", MANAGER));
        instance.setBundleId(-1L);
        instance.setFlowName("Claims (archived)");

        Instance submission = composer.compose(instance, List.of(),
                List.of(task("manager", MANAGER, FlowApprovalTaskStatus.PENDING, at(1), null, null)));

        assertEquals(List.of("manager"), submission.getSteps().stream().map(Step::getNodeId).toList());
        assertEquals(StepState.CURRENT, submission.getSteps().getFirst().getState());
        assertEquals("Claims (archived)", submission.getFlowName());
    }

    // ---- fixtures ----

    private FlowApprovalStepsComposer composer() {
        ApprovalAuditReader auditReader = new ApprovalAuditReader(ledger);
        FlowAuditService auditService = new FlowAuditService(auditReader);
        ApproverResolutionService approverResolution = new ApproverResolutionService();
        PendingApprovalFactory pendingApprovalFactory = new PendingApprovalFactory(approverResolution);
        AutoDelegationService autoDelegation = new AutoDelegationService();
        ReflectionTestUtils.setField(autoDelegation, "delegationService", mock(FlowDelegationService.class));
        ReflectionTestUtils.setField(pendingApprovalFactory, "autoDelegationService", autoDelegation);
        ApprovalPathPreviewer previewer = new ApprovalPathPreviewer(new FlowGatewayRouter(), pendingApprovalFactory,
                approverResolution, new ApproverDedupService(new ApprovalLifecycleService(), auditService, auditReader),
                auditService);
        return new FlowApprovalStepsComposer(bundleRegistry, previewer, approverResolution);
    }

    private static LocalDateTime at(int hours) {
        return T0.plusHours(hours);
    }

    private static Approver row(String actorId, ApproverResult result, LocalDateTime time, String comment) {
        return Approver.builder().actor(new Actor(actorId, null)).result(result).time(time).comment(comment).build();
    }

    private static PendingApproval waiting(String nodeId, String nodeLabel, String... approvers) {
        return PendingApproval.builder().nodeId(nodeId).nodeLabel(nodeLabel)
                .approvers(new ArrayList<>(List.of(approvers))).approvedActors(new ArrayList<>())
                .approvalMode(VoteThresholdMode.ANY_ONE).requiredApprovalCount(1).build();
    }

    private static FlowInstance instance(CompiledFlowDefinition flow, FlowExecutionStatus status, PendingApproval pending) {
        FlowInstance instance = new FlowInstance();
        instance.setInstanceId("i1");
        instance.setBundleId(flow.getBundleId());
        instance.setFlowId(flow.getFlowId());
        instance.setRecordApproval(true);
        instance.setStatus(status);
        instance.setInitiatorId(SUBMITTER);
        instance.setModelName("ExpenseClaim");
        instance.setRowId("7");
        instance.setCreatedTime(T0);
        instance.setVariables(JsonUtils.objectToString(Map.of()));
        instance.setCompletedNodeIds(JsonUtils.objectToString(List.of("start")));
        instance.setPendingApprovals(JsonUtils.objectToString(pending == null ? List.of() : List.of(pending)));
        return instance;
    }

    private static FlowApprovalTask task(String nodeId, String actorId, FlowApprovalTaskStatus status,
                                         LocalDateTime start, LocalDateTime end, String comment) {
        FlowApprovalTask task = new FlowApprovalTask();
        task.setInstanceId("i1");
        task.setNodeId(nodeId);
        task.setActorId(actorId);
        task.setStatus(status);
        task.setTaskType(FlowApprovalTaskType.APPROVAL);
        task.setStartTime(start);
        task.setEndTime(end);
        task.setComment(comment);
        task.setRequiredApprovalCount(1);
        return task;
    }

    private static ApprovalActionAuditEntry entry(int sequence, ApprovalActionType action, String nodeId, String actorId,
                                                  String targetActorId, String operatorId, String comment,
                                                  LocalDateTime time) {
        return ApprovalActionAuditEntry.builder().sequence(sequence).action(action).nodeId(nodeId)
                .actorId(actorId).targetActorId(targetActorId).operatorId(operatorId).comment(comment)
                .eventTime(time).build();
    }

    /** start → each of {@code steps} in turn → end. */
    private static DesignFlowDefinition chain(String name, FlowGraphNode... steps) {
        List<FlowGraphNode> nodes = new ArrayList<>();
        List<FlowGraphEdge> edges = new ArrayList<>();
        nodes.add(FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build());
        String previous = "start";
        for (FlowGraphNode step : steps) {
            nodes.add(step);
            edges.add(FlowGraphEdge.builder().id("e" + edges.size()).source(previous).target(step.getId()).build());
            previous = step.getId();
        }
        nodes.add(FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build());
        edges.add(FlowGraphEdge.builder().id("e" + edges.size()).source(previous).target("end").build());
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("ExpenseClaim"))
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }

    /** An approval step asking {@code userIds}, any one of whom approves it. */
    private static FlowGraphNode approval(String id, String label, String... userIds) {
        Map<String, Object> config = Map.of("approverSources",
                List.of(Map.of("type", "Users", "userIds", List.of(userIds))));
        return FlowGraphNode.builder().id(id).type(FlowNodeType.APPROVAL).label(label).config(config).build();
    }
}

package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowNoApproverException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Only people whose account can act take part in an approval (START → Manager approval → END): approvers and CC
 * recipients the flow names who cannot act are passed over, a step left with nobody follows its empty-approver
 * strategy, and every action handing the step to someone who cannot act is refused, naming them. Each refusal names
 * the person and the step by its label.
 */
class ActorAccountScenarioTest {

    private static final String FLOW_ID = "9400";
    private static final String INITIATOR = "1001";
    private static final String ANN = "2001";
    private static final String BOB = "2002";
    private static final String CAT = "2003";
    private static final String DEE = "3001";
    private static final String EVE = "3002";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService =
            new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final List<FlowNotificationEvent> notices = new ArrayList<>();

    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithAccounts(
            bundleRegistry,
            new StubFlowInstanceStore(),
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler())),
            approvalHandler,
            notices::add,
            // Bob's and Eve's accounts are frozen.
            TestActorAccounts.of(Map.of(2002L, "Frozen", 3002L, "Frozen"),
                    Map.of(ANN, "Ann Lee", BOB, "Bob Wu", CAT, "Cat Ng", DEE, "Dee Tan", EVE, "Eve Yu")));

    @Test
    void approversAndCcRecipientsWhoCannotActArePassedOver() {
        FlowExecutionState state = start(List.of(ANN, BOB, CAT), "Skip", List.of(DEE, EVE));

        assertEquals(List.of(ANN, CAT), state.getPendingApprovals().getFirst().getApprovers());
        assertEquals(List.of(DEE), ccTargets(state), "the CC rule copies in only who can act");
    }

    /**
     * A step that only names its people, none of whom can act any more, stops and says why — its flow needs changing —
     * whatever empty-approver strategy it holds: the strategy is for a step that finds its approvers by rule.
     */
    @Test
    void aStepNamingOnlyPeopleWhoCannotActStopsWhateverItsStrategy() {
        for (String strategy : List.of("Skip", "AutoApprove", "AssignToRole")) {
            FlowNoApproverException refused = assertThrows(FlowNoApproverException.class,
                    () -> start(List.of(BOB), strategy, List.of()), strategy);
            assertEquals("No approver was found for step 'Manager approval': none of its approvers can approve. "
                    + "Ask your HR administrator to set one up, then try again.", refused.getMessage(), strategy);
        }
    }

    @Test
    void handingTheStepToSomeoneWhoCannotActIsRefused() {
        String instanceId = start(List.of(ANN, CAT), "Error", List.of()).getInstanceId();

        List<Function<String, FlowExecutionState>> actions = List.of(
                target -> engine.transfer(targeted(new FlowTransferRequest(), instanceId, target)),
                target -> {
                    FlowReassignRequest reassign = targeted(new FlowReassignRequest(), instanceId, target);
                    reassign.setOperatorId("admin-1");
                    reassign.setComment("Ann is away");
                    return engine.reassign(reassign);
                });
        for (Function<String, FlowExecutionState> action : actions) {
            FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                    () -> action.apply(BOB));
            assertEquals("Bob Wu's account is frozen.", refused.getMessage());
        }
        assertEquals(List.of(ANN, CAT), engine.getInstance(instanceId).orElseThrow()
                .getPendingApprovals().getFirst().getApprovers(), "nothing moved");
    }

    @Test
    void aCcIncludingSomeoneWhoCannotActIsRefusedAsAWhole() {
        String instanceId = start(List.of(ANN, CAT), "Error", List.of()).getInstanceId();
        FlowCcRequest cc = new FlowCcRequest();
        cc.setInstanceId(instanceId);
        cc.setNodeId("manager");
        cc.setActorId(ANN);
        cc.setTargetActorIds(List.of(DEE, BOB, EVE));

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class, () -> engine.cc(cc));

        assertEquals("Bob Wu's account is frozen; Eve Yu's account is frozen.", refused.getMessage());
        assertEquals(List.of(), ccTargets(engine.getInstance(instanceId).orElseThrow()), "nobody was CC'd");
    }

    @Test
    void refusalsNameThePersonAndTheStep() {
        String instanceId = start(List.of(ANN, CAT), "Error", List.of()).getInstanceId();

        FlowActionValidationException alreadyThere = assertThrows(FlowActionValidationException.class,
                () -> engine.transfer(targeted(new FlowTransferRequest(), instanceId, CAT)));
        assertEquals("Cat Ng is already an approver of step 'Manager approval'.", alreadyThere.getMessage());

        FlowTransferRequest notTheirs = targeted(new FlowTransferRequest(), instanceId, DEE);
        notTheirs.setActorId("3009");
        FlowActionValidationException notAnApprover = assertThrows(FlowActionValidationException.class,
                () -> engine.transfer(notTheirs));
        assertEquals("3009 is not an approver of step 'Manager approval'.", notAnApprover.getMessage(),
                "someone without a name is named by their id");
    }

    /** Starts an approval: Manager approval names {@code approvers}, the flow CCs {@code ccRecipients} on submit. */
    private FlowExecutionState start(List<String> approvers, String emptyApproverStrategy, List<String> ccRecipients) {
        Map<String, Object> config = new HashMap<>();
        config.put("approverSources", List.of(Map.of("type", "Users", "userIds", approvers)));
        config.put("emptyApproverStrategy", emptyApproverStrategy);
        publishService.publish(FLOW_ID, DesignFlowDefinition.builder()
                .name("Leave")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .cc(ccRecipients.isEmpty() ? null : List.of(FlowCcRule.builder()
                        .timing(CcTiming.ON_SUBMIT).recipientSource(Map.of("type", "Users", "userIds", ccRecipients)).build()))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("Start").build(),
                                FlowGraphNode.builder().id("manager").type(FlowNodeType.APPROVAL)
                                        .label("Manager approval").config(config).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("End").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("manager").build(),
                                FlowGraphEdge.builder().id("e2").source("manager").target("end").build()))
                        .build())
                .build());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        return engine.start(start);
    }

    /** Ann handing her place on Manager approval to {@code target}. */
    private static <R extends AbstractFlowNodeTargetActorRequest> R targeted(R request, String instanceId, String target) {
        request.setInstanceId(instanceId);
        request.setNodeId("manager");
        request.setActorId(ANN);
        request.setTargetActorId(target);
        return request;
    }

    private static List<String> ccTargets(FlowExecutionState state) {
        return state.getApprovalAuditDelta() == null ? List.of() : state.getApprovalAuditDelta().stream()
                .filter(entry -> ApprovalActionType.CC.equals(entry.getAction()))
                .map(ApprovalActionAuditEntry::getTargetActorId)
                .toList();
    }
}

package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowApproveRequest;
import io.softa.starter.flow.runtime.api.FlowRetryRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.handler.ApprovalNodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.NodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.NodeOutcome;
import io.softa.starter.flow.runtime.handler.PassThroughNodeExecutionHandler;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.PersistingFlowInstanceStore;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The steps after an approval failing (zingkey-hcm#2385): the approval stands — the approver's request goes
 * through — and the instance fails at the step that failed, remembering where to run again from, its initiator and
 * the tenant's administrators told. An administrator runs the steps again: the approval goes on, or fails again.
 */
class StepsAfterApprovalScenarioTest {

    private static final String FLOW_ID = "9500";
    private static final String INITIATOR = "initiator-1";
    private static final String ALICE = "alice";
    private static final String ADMIN = "hr-admin";

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService = new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final ApprovalNodeExecutionHandler approvalHandler = new ApprovalNodeExecutionHandler();
    private final Webhook webhook = new Webhook();
    private final PersistingFlowInstanceStore store = new PersistingFlowInstanceStore();
    private final List<FlowNotificationEvent> told = new ArrayList<>();
    private final FlowRuntimeEngine engine = TestFlowRuntimeFactory.createWithNotifications(
            bundleRegistry,
            store,
            new DefaultNodeHandlerRegistry(List.of(approvalHandler, new PassThroughNodeExecutionHandler(), webhook)),
            approvalHandler,
            told::add);

    @Test
    void aStepFailingAfterTheApprovalFailsTheInstanceAndTheApprovalStands() {
        FlowExecutionState started = start();
        webhook.answering = false;

        FlowExecutionState state = engine.approve(approve(started.getInstanceId()));

        assertThat(state.getStatus()).isEqualTo(FlowExecutionStatus.FAILED);
        assertThat(state.getFailedNodeId()).isEqualTo("hook");
        assertThat(state.getErrorMessage()).isEqualTo("Webhook answered 502");
        assertThat(state.getRetryAfterNodeId()).isEqualTo("approval");
        // As the approval left it: approved, and nothing after it done.
        assertThat(state.getCompletedNodeIds()).contains("approval").doesNotContain("hook", "end");
        assertThat(state.getPendingApprovals()).isEmpty();
        assertThat(store.get(started.getInstanceId()).orElseThrow().getStatus()).isEqualTo(FlowExecutionStatus.FAILED);
        assertThat(told).filteredOn(FlowNotificationEvent.FlowFailed.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((FlowNotificationEvent.FlowFailed) event).failedNodeId()).isEqualTo("hook"));
    }

    @Test
    void runAgainTheApprovalGoesOnOnceTheStepsGoThrough() {
        FlowExecutionState started = start();
        webhook.answering = false;
        engine.approve(approve(started.getInstanceId()));
        webhook.answering = true;

        FlowExecutionState state = engine.retry(retry(started.getInstanceId()));

        assertThat(state.getStatus()).isEqualTo(FlowExecutionStatus.COMPLETED);
        assertThat(state.getRetryAfterNodeId()).isNull();
        assertThat(state.getFailedNodeId()).isNull();
        assertThat(state.getErrorMessage()).isNull();
        assertThat(state.getCompletedNodeIds()).contains("approval", "hook", "end");
        assertThat(webhook.calls).isEqualTo(2);
        ApprovalActionAuditEntry retried = state.getApprovalAuditDelta().stream()
                .filter(entry -> ApprovalActionType.RETRY.equals(entry.getAction())).findFirst().orElseThrow();
        assertThat(retried.getActorId()).isEqualTo(ADMIN);
        assertThat(retried.getNodeId()).isEqualTo("approval");
    }

    @Test
    void runAgainAndFailingAgainTheInstanceFailsAgain() {
        FlowExecutionState started = start();
        webhook.answering = false;
        engine.approve(approve(started.getInstanceId()));

        FlowExecutionState state = engine.retry(retry(started.getInstanceId()));

        assertThat(state.getStatus()).isEqualTo(FlowExecutionStatus.FAILED);
        assertThat(state.getRetryAfterNodeId()).isEqualTo("approval");
        assertThat(state.getFailedNodeId()).isEqualTo("hook");
        assertThat(told).filteredOn(FlowNotificationEvent.FlowFailed.class::isInstance).hasSize(2);
    }

    @Test
    void onlyAnApprovalThatFailedInTheStepsAfterAnApprovalRunsAgain() {
        FlowExecutionState waiting = start();

        assertThrows(FlowActionValidationException.class, () -> engine.retry(retry(waiting.getInstanceId())));

        // Failed, but not after an approval: there is nowhere to run again from.
        FlowExecutionState failed = store.get(waiting.getInstanceId()).orElseThrow();
        failed.setStatus(FlowExecutionStatus.FAILED);
        store.save(failed);
        assertThrows(FlowActionValidationException.class, () -> engine.retry(retry(waiting.getInstanceId())));
    }

    private FlowExecutionState start() {
        publishService.publish(FLOW_ID, flow());
        FlowStartRequest start = new FlowStartRequest();
        start.setFlowId(FLOW_ID);
        start.setInitiatorId(INITIATOR);
        return engine.start(start);
    }

    private static FlowApproveRequest approve(String instanceId) {
        FlowApproveRequest request = new FlowApproveRequest();
        request.setInstanceId(instanceId);
        request.setNodeId("approval");
        request.setActorId(ALICE);
        return request;
    }

    private static FlowRetryRequest retry(String instanceId) {
        FlowRetryRequest request = new FlowRetryRequest();
        request.setInstanceId(instanceId);
        request.setActorId(ADMIN);
        return request;
    }

    /** Start, Alice's approval, a webhook, end — the webhook configured as the designer writes it. */
    private static DesignFlowDefinition flow() {
        return DesignFlowDefinition.builder()
                .name("Steps after approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("approval")
                                        .config(Map.of("approvers", List.of(ALICE), "approvalMode", "AnyOne")).build(),
                                FlowGraphNode.builder().id("hook").type(FlowNodeType.CALL_WEBHOOK).label("Notify payroll")
                                        .config(Map.of("url", "https://example.com/hook")).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("hook").build(),
                                FlowGraphEdge.builder().id("e3").source("hook").target("end").build()))
                        .build())
                .build();
    }

    /** A webhook that answers, or answers 502. */
    private static final class Webhook implements NodeExecutionHandler {

        private boolean answering = true;
        private int calls;

        @Override
        public boolean supports(FlowNodeType flowNodeType) {
            return FlowNodeType.CALL_WEBHOOK.equals(flowNodeType);
        }

        @Override
        public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
            calls++;
            if (!answering) {
                throw new IllegalStateException("Webhook answered 502");
            }
            return new NodeOutcome.Completed(Map.of("hookStatus", 200));
        }
    }
}

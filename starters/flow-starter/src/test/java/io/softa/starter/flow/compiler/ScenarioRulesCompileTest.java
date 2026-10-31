package io.softa.starter.flow.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.WithdrawPolicy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each scenario takes at compile time: the node types its flows may use, the trigger they must
 * declare — their start contract — and the settings that belong to it.
 */
class ScenarioRulesCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    private static final FlowGraphNode APPROVAL = FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL)
            .label("Manager").config(Map.of("approvers", List.of("manager"))).build();
    private static final FlowGraphNode TIMER = FlowGraphNode.builder().id("timer").type(FlowNodeType.TIMER)
            .label("Wait").config(Map.of("durationSeconds", 3600L)).build();

    @Test
    void anApprovalNodeIsRefusedOutsideAnApprovalFlow() {
        assertTrue(has(flow(FlowScenario.AUTOMATION, new ApiTrigger(null), APPROVAL), "INVALID_SCENARIO_NODE", "approval"));
        assertFalse(has(flow(FlowScenario.APPROVAL, submit(), APPROVAL), "INVALID_SCENARIO_NODE", "approval"));
    }

    @Test
    void stateNodesBelongToApprovalAndAutomationOnly() {
        assertFalse(has(flow(FlowScenario.APPROVAL, submit(), TIMER), "INVALID_SCENARIO_NODE", "timer"));
        assertFalse(has(flow(FlowScenario.AUTOMATION, new ApiTrigger(null), TIMER), "INVALID_SCENARIO_NODE", "timer"));
        assertTrue(has(flow(FlowScenario.VALIDATION, change(), TIMER), "INVALID_SCENARIO_NODE", "timer"));
    }

    @Test
    void everyFlowDeclaresItsTrigger() {
        assertTrue(has(flow(FlowScenario.AUTOMATION, null), "MISSING_TRIGGER", null));
        assertTrue(has(flow(FlowScenario.APPROVAL, null), "MISSING_TRIGGER", null));
    }

    @Test
    void anApprovalFlowStartsOnlyBySubmission() {
        assertTrue(has(flow(FlowScenario.APPROVAL, new ApiTrigger(null)), "TRIGGER_NOT_ALLOWED", null));
        assertTrue(has(flow(FlowScenario.AUTOMATION, submit()), "TRIGGER_NOT_ALLOWED", null));
    }

    @Test
    void apiAndSubflowTriggersBelongToAutomation() {
        assertFalse(has(flow(FlowScenario.AUTOMATION, new ApiTrigger(null)), "TRIGGER_NOT_ALLOWED", null));
        assertFalse(has(flow(FlowScenario.AUTOMATION, new SubflowTrigger()), "TRIGGER_NOT_ALLOWED", null));
        assertTrue(has(flow(FlowScenario.VALIDATION, new ApiTrigger(null)), "TRIGGER_NOT_ALLOWED", null));
        assertTrue(has(flow(FlowScenario.COMPUTE, new SubflowTrigger()), "TRIGGER_NOT_ALLOWED", null));
        assertFalse(has(flow(FlowScenario.COMPUTE, new FieldChangeTrigger("LeaveRequest", List.of())), "TRIGGER_NOT_ALLOWED", null));
    }

    @Test
    void approvalSettingsBelongToApprovalFlows() {
        assertTrue(has(builder(FlowScenario.AUTOMATION, new ApiTrigger(null)).withdrawPolicy(WithdrawPolicy.UNTIL_COMPLETED).build(),
                "APPROVAL_SETTING_OUTSIDE_APPROVAL", null));
        // Never is what any other flow has anyway.
        assertFalse(has(builder(FlowScenario.AUTOMATION, new ApiTrigger(null)).withdrawPolicy(WithdrawPolicy.NEVER).build(),
                "APPROVAL_SETTING_OUTSIDE_APPROVAL", null));
        assertTrue(has(builder(FlowScenario.AUTOMATION, new ApiTrigger(null)).approverDedup(ApproverDedupStrategy.GLOBAL)
                .build(), "APPROVAL_SETTING_OUTSIDE_APPROVAL", null));
        assertTrue(has(builder(FlowScenario.AUTOMATION, new ApiTrigger(null)).urgeEnabled(true).build(),
                "APPROVAL_SETTING_OUTSIDE_APPROVAL", null));
        assertTrue(has(builder(FlowScenario.AUTOMATION, new ApiTrigger(null)).urgeIntervalMinutes(60).build(),
                "APPROVAL_SETTING_OUTSIDE_APPROVAL", null));
        assertFalse(has(builder(FlowScenario.APPROVAL, submit()).withdrawPolicy(WithdrawPolicy.BEFORE_FIRST_ACTION)
                .urgeEnabled(false).urgeIntervalMinutes(60).approverDedup(ApproverDedupStrategy.GLOBAL).build(),
                "APPROVAL_SETTING_OUTSIDE_APPROVAL", null));
    }

    @Test
    void theUrgeIntervalIsAtLeastAMinute() {
        assertTrue(has(builder(FlowScenario.APPROVAL, submit()).urgeIntervalMinutes(0).build(), "INVALID_URGE_INTERVAL", null));
        assertTrue(has(builder(FlowScenario.APPROVAL, submit()).urgeIntervalMinutes(-5).build(), "INVALID_URGE_INTERVAL", null));
        assertFalse(has(builder(FlowScenario.APPROVAL, submit()).urgeIntervalMinutes(1).build(), "INVALID_URGE_INTERVAL", null));
    }

    @Test
    void ccRulesBelongToApprovalFlowsAndNameTheirRecipientsOneWay() {
        FlowCcRule named = FlowCcRule.builder().timing(CcTiming.ON_APPROVED).recipients(List.of("u-1")).build();
        assertTrue(has(builder(FlowScenario.AUTOMATION, new ApiTrigger(null)).cc(List.of(named)).build(),
                "APPROVAL_SETTING_OUTSIDE_APPROVAL", null));
        assertFalse(has(builder(FlowScenario.APPROVAL, submit()).cc(List.of(named)).build(), "INVALID_CC_RULE", null));

        FlowCcRule whenless = FlowCcRule.builder().recipients(List.of("u-1")).build();
        FlowCcRule nobody = FlowCcRule.builder().timing(CcTiming.ON_SUBMIT).build();
        FlowCcRule both = FlowCcRule.builder().timing(CcTiming.ON_SUBMIT).recipients(List.of("u-1"))
                .recipientSource(Map.of("type", "Supervisor")).build();
        for (FlowCcRule invalid : List.of(whenless, nobody, both)) {
            assertTrue(has(builder(FlowScenario.APPROVAL, submit()).cc(List.of(invalid)).build(), "INVALID_CC_RULE", null));
        }
    }

    @Test
    void syncBelongsToAutomationFlowsThatDoNotWait() {
        assertTrue(has(builder(FlowScenario.APPROVAL, submit()).sync(true).build(), "SYNC_OUTSIDE_AUTOMATION", null));
        assertFalse(has(builder(FlowScenario.AUTOMATION, change()).sync(true).build(), "SYNC_OUTSIDE_AUTOMATION", null));
        assertTrue(has(builder(FlowScenario.AUTOMATION, change(), TIMER).sync(true).build(), "SYNC_FLOW_WAITS", "timer"));
    }

    private boolean has(DesignFlowDefinition definition, String code, String nodeId) {
        List<CompileDiagnostic> diagnostics = compiler.validate(definition);
        return diagnostics.stream().anyMatch(d -> code.equals(d.code()) && (nodeId == null || nodeId.equals(d.nodeId())));
    }

    private static SubmitTrigger submit() {
        return new SubmitTrigger("LeaveRequest");
    }

    private static EntityChangeTrigger change() {
        return new EntityChangeTrigger("LeaveRequest", List.of(), null);
    }

    private static DesignFlowDefinition flow(FlowScenario scenario, TriggerSource trigger, FlowGraphNode... middle) {
        return builder(scenario, trigger, middle).build();
    }

    /** start → {@code middle} (when given) → end. */
    private static DesignFlowDefinition.DesignFlowDefinitionBuilder builder(FlowScenario scenario, TriggerSource trigger,
                                                                          FlowGraphNode... middle) {
        List<FlowGraphNode> nodes = new ArrayList<>();
        List<FlowGraphEdge> edges = new ArrayList<>();
        nodes.add(FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build());
        String previous = "start";
        for (FlowGraphNode node : middle) {
            nodes.add(node);
            edges.add(FlowGraphEdge.builder().id("to-" + node.getId()).source(previous).target(node.getId()).build());
            previous = node.getId();
        }
        nodes.add(FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build());
        edges.add(FlowGraphEdge.builder().id("to-end").source(previous).target("end").build());
        return DesignFlowDefinition.builder()
                .name(scenario.getType() + " flow")
                .scenario(scenario)
                .trigger(trigger)
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build());
    }
}

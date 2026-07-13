package io.softa.starter.flow.compiler;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.api.FlowCompileException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.enums.*;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;

import static org.junit.jupiter.api.Assertions.*;

class DefaultFlowCompilerTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void shouldCompileApprovalFlowWithPostApprovalAutomation() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("leave-approval")
                .name("Leave Approval")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("managerApproval", FlowNodeType.APPROVAL),
                                callServiceNode("syncLeaveBalance"),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "managerApproval"),
                                edge("e2", "managerApproval", "syncLeaveBalance"),
                                edge("e3", "syncLeaveBalance", "end")
                        ))
                        .build())
                .build();

        CompiledFlowDefinition compiled = compiler.compile(definition);

        assertEquals(List.of("start"), compiled.getEntryNodeIds());
        assertEquals(List.of("end"), compiled.getTerminalNodeIds());
        assertEquals(List.of("start", "managerApproval", "syncLeaveBalance", "end"), compiled.getTopologicalOrder());
        assertTrue(compiled.getCapabilitySummary().isHasApproval());
        assertFalse(compiled.getCapabilitySummary().isHasSubflow());
        assertFalse(compiled.getCapabilitySummary().isHasParallelGateway());
    }

    @Test
    void shouldCompileAutomationFlowWithSubflowAndParallelGateway() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("order-automation")
                .name("Order Automation")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("fork", FlowNodeType.PARALLEL_FORK),
                                taskNode("notify", FlowNodeType.SEND_EMAIL,
                                        Map.of("to", List.of("{{ initiatorEmail }}"), "templateCode", "notify-tpl")),
                                node("subflow", FlowNodeType.SUBFLOW),
                                node("join", FlowNodeType.PARALLEL_JOIN),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "fork"),
                                edge("e2", "fork", "notify"),
                                edge("e3", "fork", "subflow"),
                                edge("e4", "notify", "join"),
                                edge("e5", "subflow", "join"),
                                edge("e6", "join", "end")
                        ))
                        .build())
                .build();

        CompiledFlowDefinition compiled = compiler.compile(definition);

        assertEquals(List.of("start"), compiled.getEntryNodeIds());
        assertTrue(compiled.getCapabilitySummary().isHasSubflow());
        assertTrue(compiled.getCapabilitySummary().isHasParallelGateway());
        assertEquals(6, compiled.getNodeIndex().size());
        assertEquals(6, compiled.getTransitionIndex().size());
    }

    @Test
    void shouldRejectApprovalNodeInComputeScenario() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("compute-with-approval")
                .name("Compute With Approval")
                .scenario(FlowScenario.COMPUTE)
                .trigger(new FieldChangeTrigger())
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("approval", FlowNodeType.APPROVAL),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> d.message().contains("lightweight nodes")));
    }

    @Test
    void shouldRejectTaskNodeWithoutConfig() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("missing-task-config")
                .name("Missing Task Config")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("service", FlowNodeType.CALL_SERVICE),  // no config
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "service"),
                                edge("e2", "service", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> d.message().contains("must define config")));
    }

    @Test
    void shouldRejectNodeWithMultipleDefaultOutgoingEdges() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("invalid-default-edges")
                .name("Invalid Default Edges")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("left", FlowNodeType.END),
                                node("right", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "left"),    // no condition = default
                                edge("e2", "start", "right")    // no condition = default
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> d.message().contains("one default edge")));
    }

    @Test
    void shouldCompileFlowWithAllowInitiatorWithdraw() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("withdraw-flow")
                .name("Withdraw Flow")
                .scenario(FlowScenario.PROCESS)
                .allowInitiatorWithdraw(true)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                callServiceNode("service"),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "service"),
                                edge("e2", "service", "end")
                        ))
                        .build())
                .build();

        CompiledFlowDefinition compiled = compiler.compile(definition);
        assertTrue(compiled.getAllowInitiatorWithdraw());
    }

    @Test
    void shouldCompileApprovalNodeReturnPolicy() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("returnable-approval")
                .name("Returnable Approval")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "returnEnabled", true,
                                                "returnTarget", "Initiator"
                                        ))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        CompiledFlowDefinition compiled = compiler.compile(definition);

        CompiledFlowNode approvalNode = compiled.getNodeIndex().get("approval");
        ApprovalNodeConfig cfg = (ApprovalNodeConfig) approvalNode.getParsedConfig();
        assertNotNull(cfg);
        assertTrue(cfg.getReturnEnabled());
        assertEquals(ApprovalReturnTarget.INITIATOR, cfg.getReturnTarget());
    }

    @Test
    void shouldCompileApprovalNodePreviousApprovalReturnPolicy() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("return-to-previous-approval")
                .name("Return To Previous Approval")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("firstApproval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("firstApproval")
                                        .config(Map.of(
                                                "returnEnabled", true,
                                                "returnTarget", "PreviousApproval"
                                        ))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "firstApproval"),
                                edge("e2", "firstApproval", "end")
                        ))
                        .build())
                .build();

        CompiledFlowDefinition compiled = compiler.compile(definition);

        ApprovalNodeConfig cfg = (ApprovalNodeConfig) compiled.getNodeIndex().get("firstApproval").getParsedConfig();
        assertNotNull(cfg);
        assertEquals(ApprovalReturnTarget.PREVIOUS_APPROVAL, cfg.getReturnTarget());
    }

    @Test
    void shouldCompileApprovalNodeWithUnanimousMode() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("unanimous-approval")
                .name("Unanimous Approval")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvalMode", VoteThresholdMode.UNANIMOUS,
                                                "approvers", List.of("manager", "hr")))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        CompiledFlowDefinition compiled = compiler.compile(definition);

        assertEquals(VoteThresholdMode.UNANIMOUS,
                compiled.getNodeIndex().get("approval").getConfig().get("approvalMode"));
    }

    @Test
    void shouldRejectUnanimousApprovalModeWithoutApprovers() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("invalid-unanimous-approval")
                .name("Invalid Unanimous Approval")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of("approvalMode", VoteThresholdMode.UNANIMOUS))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> d.message().contains("Unanimous approval mode")));
    }

    @Test
    void shouldRejectMinCountApprovalModeWhenThresholdExceedsApprovers() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("invalid-min-count-approval")
                .name("Invalid Min Count Approval")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvalMode", VoteThresholdMode.MIN_COUNT,
                                                "approvers", List.of("manager", "hr"),
                                                "minCount", 3))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "config.minCount".equals(d.field())));
    }

    @Test
    void shouldCompileMinCountApprovalModeWithDynamicApproverSource() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("dynamic-min-count-approval")
                .name("Dynamic Min Count Approval")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvalMode", VoteThresholdMode.MIN_COUNT,
                                                "minCount", 2,
                                                "approverSource", Map.of(
                                                        "type", "VariableList",
                                                        "variable", "managerIds")))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        CompiledFlowDefinition compiled = compiler.compile(definition);

        assertEquals(VoteThresholdMode.MIN_COUNT, compiled.getNodeIndex().get("approval").getConfig().get("approvalMode"));
        assertEquals(2, compiled.getNodeIndex().get("approval").getConfig().get("minCount"));
        assertEquals("VariableList",
                ((Map<?, ?>) compiled.getNodeIndex().get("approval").getConfig().get("approverSource")).get("type"));
    }

    @Test
    void shouldRejectPercentageApprovalModeOutsideRange() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("invalid-percentage-approval")
                .name("Invalid Percentage Approval")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvalMode", VoteThresholdMode.PERCENTAGE,
                                                "approvers", List.of("manager", "hr"),
                                                "percentage", 0))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "config.percentage".equals(d.field())));
    }

    @Test
    void shouldRejectMinCountRejectModeWhenThresholdExceedsApprovers() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("invalid-min-count-reject")
                .name("Invalid Min Count Reject")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvers", List.of("manager", "hr"),
                                                "rejectMode", VoteThresholdMode.MIN_COUNT,
                                                "rejectMinCount", 3))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "config.rejectMinCount".equals(d.field())));
    }

    @Test
    void shouldRejectDeadlockingApprovalAndRejectThresholds() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("deadlocking-thresholds")
                .name("Deadlocking Thresholds")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvalMode", VoteThresholdMode.MIN_COUNT,
                                                "minCount", 2,
                                                "rejectMode", VoteThresholdMode.MIN_COUNT,
                                                "rejectMinCount", 2,
                                                "approvers", List.of("manager", "hr")))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "DEADLOCK_THRESHOLD".equals(d.code())));
    }

    @Test
    void shouldRejectApprovalReturnEnabledWithoutTarget() {
        // returnEnabled=true but returnTarget not set — should fail with MISSING_RETURN_TARGET
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("invalid-return-policy")
                .name("Invalid Return Policy")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of("returnEnabled", true)) // no returnTarget
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "MISSING_RETURN_TARGET".equals(d.code())));
    }

    @Test
    void shouldRejectForEachNodeAsUnsupported() {
        // FOR_EACH child-node iteration is not implemented by the runtime;
        // it must be rejected at compile time so it can never publish and silently no-op.
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("foreach-flow")
                .name("ForEach Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("loop")
                                        .type(FlowNodeType.FOR_EACH)
                                        .label("loop")
                                        .config(Map.of(
                                                "collectionExpression", "{{ items }}",
                                                "itemVariable", "item",
                                                "childNodeIds", List.of("end")))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "loop"),
                                edge("e2", "loop", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "UNSUPPORTED_NODE_TYPE".equals(d.code())));
    }

    @Test
    void shouldRejectHumanTaskNodeAsUnsupported() {
        // The runtime has no human-task wait (assignee resolution, completion API); the node
        // must be rejected at compile time so it can never publish and fail at execution.
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("human-task-flow")
                .name("Human Task Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("confirm")
                                        .type(FlowNodeType.HUMAN_TASK)
                                        .label("confirm")
                                        .config(Map.of("assigneeExpression", "{{ initiatorId }}"))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "confirm"),
                                edge("e2", "confirm", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "UNSUPPORTED_NODE_TYPE".equals(d.code())));
    }

    @Test
    void shouldRejectCreateRecordWithoutModelName() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("invalid-create")
                .name("Invalid Create")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("create")
                                        .type(FlowNodeType.CREATE_RECORD)
                                        .label("create")
                                        .config(Map.of("input", Map.of("rowTemplate", Map.of("name", "x"))))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "create"),
                                edge("e2", "create", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "config.input.modelName".equals(d.field())));
    }

    @Test
    void shouldRejectCallServiceWithMismatchedArgTypesArity() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("invalid-argtypes")
                .name("Invalid ArgTypes")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("svc")
                                        .type(FlowNodeType.CALL_SERVICE)
                                        .label("svc")
                                        .config(Map.of("input", Map.of(
                                                "beanName", "demoService",
                                                "methodName", "run",
                                                "args", List.of(1),
                                                "argTypes", List.of("int", "long"))))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "svc"),
                                edge("e2", "svc", "end")
                        ))
                        .build())
                .build();

        FlowCompileException exception = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "ARG_TYPES_ARITY".equals(d.code())));
    }

    // ==================== validate() — success-shaped diagnostics ====================

    @Test
    void validate_returnsEmptyListForCompilableDefinition() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("valid-flow")
                .name("Valid Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(node("start", FlowNodeType.START), node("end", FlowNodeType.END)))
                        .edges(List.of(edge("e1", "start", "end")))
                        .build())
                .build();

        assertTrue(compiler.validate(definition).isEmpty());
    }

    @Test
    void validate_collectsAllStructuralDefectsWithAnchors() {
        // duplicate node id + missing node type + dangling edge endpoint — all in one round
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("broken-flow")
                .name("Broken Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("start", FlowNodeType.END),
                                FlowGraphNode.builder().id("untyped").build()))
                        .edges(List.of(FlowGraphEdge.builder().id("e1").source("start").build()))
                        .build())
                .build();

        List<CompileDiagnostic> diagnostics = compiler.validate(definition);

        assertEquals(3, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(d ->
                "DUPLICATE_NODE_ID".equals(d.code()) && "start".equals(d.nodeId())));
        assertTrue(diagnostics.stream().anyMatch(d ->
                "MISSING_NODE_TYPE".equals(d.code()) && "untyped".equals(d.nodeId())));
        assertTrue(diagnostics.stream().anyMatch(d ->
                "MISSING_EDGE_ENDPOINT".equals(d.code()) && "e1".equals(d.edgeId())));
    }

    @Test
    void validate_anchorsCycleDiagnosticsToNodes() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("cyclic-flow")
                .name("Cyclic Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                taskNode("a", FlowNodeType.CALL_WEBHOOK),
                                taskNode("b", FlowNodeType.CALL_WEBHOOK),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(
                                edge("e1", "start", "a"),
                                edge("e2", "a", "b"),
                                edge("e3", "b", "a"),
                                edge("e4", "b", "end")))
                        .build())
                .build();

        List<CompileDiagnostic> diagnostics = compiler.validate(definition);

        List<String> cycleNodes = diagnostics.stream()
                .filter(d -> "GRAPH_CYCLE".equals(d.code()))
                .map(CompileDiagnostic::nodeId)
                .sorted()
                .toList();
        // a and b form the cycle; end is only reachable through it
        assertEquals(List.of("a", "b", "end"), cycleNodes);
        assertTrue(diagnostics.stream().allMatch(d ->
                d.severity() == CompileDiagnostic.Severity.ERROR));
    }

    @Test
    void validate_anchorsUnknownEdgeEndpointsToTheEdge() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("dangling-flow")
                .name("Dangling Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(node("start", FlowNodeType.START), node("end", FlowNodeType.END)))
                        .edges(List.of(edge("e1", "start", "ghost")))
                        .build())
                .build();

        List<CompileDiagnostic> diagnostics = compiler.validate(definition);

        assertTrue(diagnostics.stream().anyMatch(d ->
                "UNKNOWN_EDGE_TARGET".equals(d.code()) && "e1".equals(d.edgeId())));
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder()
                .id(id)
                .type(type)
                .label(id)
                .build();
    }

    private static FlowGraphNode taskNode(String id, FlowNodeType type) {
        return FlowGraphNode.builder()
                .id(id)
                .type(type)
                .label(id)
                .config(Map.of("input", Map.of()))
                .build();
    }

    /** Task node with a shaped input — typed node types now require their mandatory keys at compile. */
    private static FlowGraphNode taskNode(String id, FlowNodeType type, Map<String, Object> input) {
        return FlowGraphNode.builder()
                .id(id)
                .type(type)
                .label(id)
                .config(Map.of("input", input))
                .build();
    }

    /** CALL_SERVICE is a typed node type — the compiler now requires beanName/methodName in its input. */
    private static FlowGraphNode callServiceNode(String id) {
        return FlowGraphNode.builder()
                .id(id)
                .type(FlowNodeType.CALL_SERVICE)
                .label(id)
                .config(Map.of("input", Map.of("beanName", "demoService", "methodName", "run")))
                .build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder()
                .id(id)
                .source(source)
                .target(target)
                .build();
    }

    private static FlowGraphEdge edge(String id, String source, String target, String conditionExpression) {
        return FlowGraphEdge.builder()
                .id(id)
                .source(source)
                .target(target)
                .conditionExpression(conditionExpression)
                .build();
    }
}

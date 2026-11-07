package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.enums.WithdrawPolicy;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;

import static org.junit.jupiter.api.Assertions.*;

class DefaultFlowCompilerTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void shouldCompileApprovalFlowWithPostApprovalAutomation() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Leave Approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
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
        assertEquals(FlowScenario.APPROVAL, compiled.getScenario());
        assertFalse(compiled.getCapabilitySummary().isHasSubflow());
        assertFalse(compiled.getCapabilitySummary().isHasParallelGateway());
    }

    @Test
    void shouldCompileAutomationFlowWithSubflowAndParallelGateway() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Order Automation")
                .scenario(FlowScenario.AUTOMATION)
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
                .name("Compute With Approval")
                .scenario(FlowScenario.COMPUTE)
                .trigger(new FieldChangeTrigger("Order", List.of()))
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
        assertTrue(exception.getDiagnostics().stream().anyMatch(d ->
                "INVALID_SCENARIO_NODE".equals(d.code()) && "approval".equals(d.nodeId())));
    }

    @Test
    void shouldRejectTaskNodeWithoutConfig() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Missing Task Config")
                .scenario(FlowScenario.AUTOMATION)
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
                .name("Invalid Default Edges")
                .scenario(FlowScenario.AUTOMATION)
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
    void shouldCompileFlowWithWithdrawAndUrgeSettings() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Withdraw Flow")
                .scenario(FlowScenario.APPROVAL)
                .withdrawPolicy(WithdrawPolicy.BEFORE_FIRST_ACTION)
                .urgeEnabled(false)
                .urgeIntervalHours(2)
                .trigger(new SubmitTrigger("LeaveRequest"))
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
        assertEquals(WithdrawPolicy.BEFORE_FIRST_ACTION, compiled.getWithdrawPolicy());
        assertEquals(false, compiled.getUrgeEnabled());
        assertEquals(2, compiled.getUrgeIntervalHours());
    }

    @Test
    void shouldCompileTheFlowsReturnPolicy() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Returnable Approval")
                .scenario(FlowScenario.APPROVAL)
                .returnEnabled(true)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        // A step saved with its own switch before: ignored, the flow's decides.
                                        .config(Map.of("returnEnabled", false, "approverSources", List.of(Map.of("type", "Users", "userIds", List.of("1001")))))
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

        // A return always goes to the initiator: nothing else to configure.
        assertTrue(compiled.getReturnEnabled());
        assertNotNull(compiled.getNodeIndex().get("approval").getParsedConfig());
    }

    @Test
    void shouldCompileApprovalNodeWithUnanimousMode() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Unanimous Approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvalMode", VoteThresholdMode.UNANIMOUS,
                                                "approverSources", List.of(Map.of("type", "Users", "userIds", List.of("manager", "hr")))))
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
                .name("Invalid Unanimous Approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
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
        // Naming nobody is what is wrong, whatever the mode: said once, as for any approval step (zingkey-hcm#2450).
        assertTrue(exception.getDiagnostics().stream().anyMatch(d -> "MISSING_APPROVERS".equals(d.code())));
    }

    @Test
    void shouldRejectMinCountApprovalModeWhenThresholdExceedsApprovers() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Invalid Min Count Approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvalMode", VoteThresholdMode.MIN_COUNT,
                                                "approverSources", List.of(Map.of("type", "Users", "userIds", List.of("manager", "hr"))),
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
                .name("Dynamic Min Count Approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
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
                                                "approverSources", List.of(Map.of(
                                                        "type", "RoleQuery",
                                                        "roleCodes", List.of("MANAGER")))))
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
        assertEquals("RoleQuery",
                ((Map<?, ?>) ((List<?>) compiled.getNodeIndex().get("approval").getConfig().get("approverSources")).getFirst())
                        .get("type"));
    }

    @Test
    void shouldRejectPercentageApprovalModeOutsideRange() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Invalid Percentage Approval")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of(
                                                "approvalMode", VoteThresholdMode.PERCENTAGE,
                                                "approverSources", List.of(Map.of("type", "Users", "userIds", List.of("manager", "hr"))),
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
    void aRejectModeAStepWasSavedWithBeforeIsIgnored() {
        // Two approvals of two beside two rejections of two used to deadlock, and three rejections of two to be
        // refused. A rejection has no threshold any more — one rejects the approval — so the old settings compile and
        // decide nothing.
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Old Reject Thresholds")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
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
                                                "rejectMinCount", 3,
                                                "approverSources", List.of(Map.of("type", "Users", "userIds", List.of("manager", "hr")))))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "approval"),
                                edge("e2", "approval", "end")
                        ))
                        .build())
                .build();

        assertDoesNotThrow(() -> compiler.compile(definition));
    }

    @Test
    void shouldRejectForEachNodeAsUnsupported() {
        // FOR_EACH child-node iteration is not implemented by the runtime;
        // it must be rejected at compile time so it can never publish and silently no-op.
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("ForEach Flow")
                .scenario(FlowScenario.AUTOMATION)
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
                .name("Human Task Flow")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
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
                .name("Invalid Create")
                .scenario(FlowScenario.AUTOMATION)
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
                .name("Invalid ArgTypes")
                .scenario(FlowScenario.AUTOMATION)
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
                .name("Valid Flow")
                .scenario(FlowScenario.AUTOMATION)
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
                .name("Broken Flow")
                .scenario(FlowScenario.AUTOMATION)
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
                .name("Cyclic Flow")
                .scenario(FlowScenario.AUTOMATION)
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
                .name("Dangling Flow")
                .scenario(FlowScenario.AUTOMATION)
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

    @Test
    void validate_requiresAStartNodeEvenWhenTheGraphHasASingleRoot() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("No Start")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(node("approval", FlowNodeType.APPROVAL), node("end", FlowNodeType.END)))
                        .edges(List.of(edge("e1", "approval", "end")))
                        .build())
                .build();

        List<CompileDiagnostic> diagnostics = compiler.validate(definition);

        assertTrue(diagnostics.stream().anyMatch(d -> "NO_START_NODE".equals(d.code())));
        // Without a Start every node would be "unreachable" — the entry rule alone reports the graph.
        assertTrue(diagnostics.stream().noneMatch(d -> "UNREACHABLE_NODE".equals(d.code())));
    }

    @Test
    void validate_rejectsMoreThanOneStartNode() {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Two Starts")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("startA", FlowNodeType.START),
                                node("startB", FlowNodeType.START),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(edge("e1", "startA", "end"), edge("e2", "startB", "end")))
                        .build())
                .build();

        List<CompileDiagnostic> diagnostics = compiler.validate(definition);

        assertTrue(diagnostics.stream().anyMatch(d -> "MULTIPLE_START_NODES".equals(d.code())
                && d.message().contains("startA") && d.message().contains("startB")));
    }

    @Test
    void validate_rejectsNodesTheStartCannotReach() {
        // approval_a → approval_b → manager: a branch with no way in from the Start, merging into the main path
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Orphan Branch")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                node("approval_a", FlowNodeType.APPROVAL),
                                node("approval_b", FlowNodeType.APPROVAL),
                                node("manager", FlowNodeType.APPROVAL),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(
                                edge("e1", "start", "manager"),
                                edge("e2", "approval_a", "approval_b"),
                                edge("e3", "approval_b", "manager"),
                                edge("e4", "manager", "end")))
                        .build())
                .build();

        List<CompileDiagnostic> diagnostics = compiler.validate(definition);

        List<String> unreachable = diagnostics.stream()
                .filter(d -> "UNREACHABLE_NODE".equals(d.code()))
                .map(CompileDiagnostic::nodeId)
                .toList();
        assertEquals(List.of("approval_a", "approval_b"), unreachable);
        assertTrue(diagnostics.stream()
                .filter(d -> "UNREACHABLE_NODE".equals(d.code()))
                .allMatch(d -> d.severity() == CompileDiagnostic.Severity.ERROR));
    }

    @Test
    void validate_requiresEveryNodeToHaveALabel() {
        // People see the label as the step's name; without one they would see the generated id
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Unnamed Step")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                labelled("approval_x7k2", FlowNodeType.APPROVAL, " "),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(
                                edge("e1", "start", "approval_x7k2"),
                                edge("e2", "approval_x7k2", "end")))
                        .build())
                .build();

        List<CompileDiagnostic> missing = compiler.validate(definition).stream()
                .filter(d -> "MISSING_NODE_LABEL".equals(d.code()))
                .toList();

        assertEquals(1, missing.size(), missing.toString());
        assertEquals(CompileDiagnostic.Severity.ERROR, missing.getFirst().severity());
        assertEquals("approval_x7k2", missing.getFirst().nodeId());
        assertEquals("label", missing.getFirst().field());
        assertThrows(FlowCompileException.class, () -> compiler.compile(definition), "publishing is refused");
    }

    @Test
    void validate_namesNodesAndEdgesAsPeopleSeeThem() {
        // A node by its label, an edge without a label by the nodes it joins — never by a generated id
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Named Steps")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                labelled("approval_x7k2", FlowNodeType.APPROVAL, "Direct Manager"),
                                labelled("approval_p3q9", FlowNodeType.APPROVAL, "HR Review"),
                                labelled("end_k1", FlowNodeType.END, "Approved")))
                        .edges(List.of(
                                edge("e1", "start", "approval_x7k2"),
                                edge("e2", "approval_x7k2", "end_k1", "amount >"),
                                edge("e3", "approval_p3q9", "end_k1")))
                        .build())
                .build();

        List<CompileDiagnostic> diagnostics = compiler.validate(definition);

        assertEquals("Node 'HR Review' cannot be reached from the Start node — connect it or delete it",
                messageOf(diagnostics, "UNREACHABLE_NODE"));
        assertEquals("Node 'Direct Manager' has invalid condition expression on edge 'Direct Manager → Approved': "
                + "amount >", messageOf(diagnostics, "INVALID_CONDITION_EXPR"));
    }

    private static String messageOf(List<CompileDiagnostic> diagnostics, String code) {
        return diagnostics.stream()
                .filter(d -> code.equals(d.code()))
                .map(CompileDiagnostic::message)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + code + " in " + diagnostics));
    }

    private static FlowGraphNode labelled(String id, FlowNodeType type, String label) {
        return FlowGraphNode.builder()
                .id(id)
                .type(type)
                .label(label)
                .build();
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

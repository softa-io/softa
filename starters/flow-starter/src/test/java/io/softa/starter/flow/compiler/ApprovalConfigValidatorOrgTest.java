package io.softa.starter.flow.compiler;

import io.softa.starter.flow.api.FlowCompileException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler validation tests for the new org-aware approver source types.
 */
class ApprovalConfigValidatorOrgTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void shouldAcceptSupervisorSourceType() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "Supervisor",
                "level", 2));

        assertDoesNotThrow(() -> compiler.compile(definition));
    }

    @Test
    void shouldRejectSupervisorWithInvalidLevel() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "Supervisor",
                "level", 0));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "INVALID_SUPERVISOR_LEVEL".equals(d.code())));
    }

    @Test
    void shouldAcceptDeptLeaderWithoutDeptId() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of("type", "DeptLeader"));

        assertDoesNotThrow(() -> compiler.compile(definition));
    }

    @Test
    void shouldAcceptRoleQueryWithRoleIds() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "RoleQuery",
                "roleIds", List.of(1, 2)));

        assertDoesNotThrow(() -> compiler.compile(definition));
    }

    @Test
    void shouldRejectRoleQueryWithEmptyRoleIds() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "RoleQuery",
                "roleIds", List.of()));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "MISSING_ROLE_IDS".equals(d.code())));
    }

    @Test
    void shouldAcceptPositionWithPositionIds() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "Position",
                "positionIds", List.of(5)));

        assertDoesNotThrow(() -> compiler.compile(definition));
    }

    @Test
    void shouldRejectPositionWithEmptyPositionIds() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "Position",
                "positionIds", List.of()));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "MISSING_POSITION_IDS".equals(d.code())));
    }

    @Test
    void shouldAcceptDepartmentWithDeptIds() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "Department",
                "deptIds", List.of(10, 20)));

        assertDoesNotThrow(() -> compiler.compile(definition));
    }

    @Test
    void shouldAcceptDepartmentWithInitiatorDeptScope() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "Department",
                "deptScope", "INITIATOR_DEPT"));

        assertDoesNotThrow(() -> compiler.compile(definition));
    }

    @Test
    void shouldRejectDepartmentWithNoDeptIdsAndNoInitiatorScope() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "Department",
                "deptScope", "SPECIFIC"));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "MISSING_DEPT_IDS".equals(d.code())));
    }

    @Test
    void shouldRejectUnknownApproverSourceType() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "UnknownType"));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "UNKNOWN_APPROVER_SOURCE_TYPE".equals(d.code())));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private DesignFlowDefinition flowWithApproverSource(Map<String, Object> approverSource) {
        return DesignFlowDefinition.builder()
                .code("org-approval-test")
                .name("Org Approval Test")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(Map.of("approverSource", approverSource))
                                        .build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()
                        ))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()
                        ))
                        .build())
                .build();
    }
}

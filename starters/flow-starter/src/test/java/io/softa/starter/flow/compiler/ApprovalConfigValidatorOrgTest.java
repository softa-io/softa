package io.softa.starter.flow.compiler;

import io.softa.starter.flow.api.FlowCompileException;

import java.util.LinkedHashMap;
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
                "deptScope", "SPECIFIC_DEPT"));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "MISSING_DEPT_IDS".equals(d.code())));
    }

    @Test
    void shouldRejectADeptScopeTheSourceDoesNotTake() {
        // ALL belongs to role queries; SPECIFIC was Department's spelling before the scopes were unified.
        for (Map<String, Object> source : List.<Map<String, Object>>of(
                Map.of("type", "DeptLeader", "deptScope", "ALL"),
                Map.of("type", "Department", "deptScope", "SPECIFIC", "deptIds", List.of(10)))) {
            FlowCompileException ex = assertThrows(FlowCompileException.class,
                    () -> compiler.compile(flowWithApproverSource(source)));
            assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "INVALID_DEPT_SCOPE".equals(d.code())), source.toString());
        }
    }

    @Test
    void shouldRequireTheDepartmentAScopeNames() {
        FlowCompileException specific = assertThrows(FlowCompileException.class, () -> compiler.compile(
                flowWithApproverSource(Map.of("type", "DeptHrbp", "deptScope", "SPECIFIC_DEPT"))));
        FlowCompileException variable = assertThrows(FlowCompileException.class, () -> compiler.compile(
                flowWithApproverSource(Map.of("type", "RoleQuery", "roleCodes", List.of("HR"), "deptScope", "VARIABLE_DEPT"))));

        assertTrue(specific.getDiagnostics().stream().anyMatch(d -> "MISSING_DEPT_IDS".equals(d.code())));
        assertTrue(variable.getDiagnostics().stream().anyMatch(d -> "MISSING_DEPT_VARIABLE".equals(d.code())));
        assertDoesNotThrow(() -> compiler.compile(flowWithApproverSource(
                Map.of("type", "DeptLeader", "deptScope", "VARIABLE_DEPT", "deptVariable", "toDeptId"))));
    }

    @Test
    void shouldRejectAnUnknownSupervisorRelativeTo() {
        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(
                flowWithApproverSource(Map.of("type", "Supervisor", "relativeTo", "MANAGER"))));

        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "INVALID_RELATIVE_TO".equals(d.code())));
        assertDoesNotThrow(() -> compiler.compile(
                flowWithApproverSource(Map.of("type", "Supervisor", "relativeTo", "INITIATOR"))));
    }

    @Test
    void shouldRejectUnknownApproverSourceType() {
        DesignFlowDefinition definition = flowWithApproverSource(Map.of(
                "type", "UnknownType"));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "UNKNOWN_APPROVER_SOURCE_TYPE".equals(d.code())));
    }

    /**
     * A step that names no group finds nobody on every run — under the default strategy every submission is refused
     * — so it is not published (zingkey-hcm#2450). A group of specific people naming nobody, or a group without a
     * type, names nobody either; the diagnostic stands on the group's people when it chose them, else on the group, and
     * on the step's groups when it has none.
     */
    @Test
    void aStepThatNamesNoApproverIsNotPublished() {
        Map<Map<String, Object>, String> fieldOf = new LinkedHashMap<>();
        fieldOf.put(Map.of(), "config.approverSources");
        fieldOf.put(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of(" ")))), "config.approverSources.0.userIds");
        fieldOf.put(Map.of("approverSources", List.of(Map.of("type", "Users"))), "config.approverSources.0.userIds");
        fieldOf.put(Map.of("approverSources", List.of(Map.of("level", 1))), "config.approverSources.0");
        fieldOf.put(Map.of("emptyApproverStrategy", "AutoApprove"), "config.approverSources");
        fieldOf.forEach((config, field) -> {
            FlowCompileException ex = assertThrows(FlowCompileException.class,
                    () -> compiler.compile(flowWithConfig(config)), config.toString());
            assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "MISSING_APPROVERS".equals(d.code())
                    && "approval".equals(d.nodeId()) && field.equals(d.field())), config.toString());
        });
        assertDoesNotThrow(() -> compiler.compile(flowWithConfig(Map.of("approverSources", List.of(Map.of("type", "Users", "userIds", List.of("1001")))))));
    }

    /**
     * Each group is judged on its own, and a diagnostic stands on the group it is about, named by its place when the
     * step has several.
     */
    @Test
    void eachGroupIsJudgedWhereItIs() {
        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(flowWithConfig(Map.of(
                "approverSources", List.of(
                        Map.of("type", "Users", "userIds", List.of("1001")),
                        Map.of("type", "Users"),
                        Map.of("type", "Supervisor", "level", 0),
                        Map.of("type", "Nobody"))))));

        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "MISSING_APPROVERS".equals(d.code())
                && "config.approverSources.1.userIds".equals(d.field())
                && d.message().startsWith("Approval node 'approval' group 2 names no approver")));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "INVALID_SUPERVISOR_LEVEL".equals(d.code())
                && "config.approverSources.2.level".equals(d.field())));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "UNKNOWN_APPROVER_SOURCE_TYPE".equals(d.code())
                && "config.approverSources.3".equals(d.field())));
        assertTrue(ex.getDiagnostics().stream().noneMatch(d -> d.field() != null
                && d.field().startsWith("config.approverSources.0")), "the first group is fine");
    }

    /**
     * A minimum count is counted over the pool: the people the groups name, each once — which a group that finds its
     * people when the step is reached may outgrow.
     */
    @Test
    void aMinimumCountIsCountedOverThePool() {
        Map<String, Object> manager = Map.of("type", "Users", "userIds", List.of("1001"));
        Map<String, Object> managerAndHr = Map.of("type", "Users", "userIds", List.of("1002", "1001"));

        assertDoesNotThrow(() -> compiler.compile(flowWithConfig(Map.of("approverSources", List.of(manager, managerAndHr),
                "approvalMode", "MinCount", "minCount", 2))));
        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(flowWithConfig(Map.of(
                "approverSources", List.of(manager, managerAndHr), "approvalMode", "MinCount", "minCount", 3))));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "INVALID_MIN_COUNT".equals(d.code())));
        assertDoesNotThrow(() -> compiler.compile(flowWithConfig(Map.of("approverSources", List.of(manager,
                Map.of("type", "Supervisor")), "approvalMode", "MinCount", "minCount", 3))));
    }

    /**
     * Settings that do not apply are not judged — the designer does not show them: the approval mode of a step with one
     * approver at most, the empty-approver roles of a step that only names its people.
     */
    @Test
    void settingsThatDoNotApplyAreNotJudged() {
        Map<String, Object> ann = Map.of("type", "Users", "userIds", List.of("1001"));
        assertDoesNotThrow(() -> compiler.compile(flowWithConfig(Map.of("approverSources", List.of(ann),
                "approvalMode", "MinCount", "minCount", 3))));
        assertDoesNotThrow(() -> compiler.compile(flowWithConfig(Map.of(
                "approverSources", List.of(Map.of("type", "Supervisor")), "approvalMode", "Percentage"))));
        assertDoesNotThrow(() -> compiler.compile(flowWithConfig(Map.of("approverSources", List.of(ann),
                "emptyApproverStrategy", "AssignToRole"))));

        FlowCompileException ex = assertThrows(FlowCompileException.class, () -> compiler.compile(flowWithConfig(Map.of(
                "approverSources", List.of(ann, Map.of("type", "Supervisor")), "emptyApproverStrategy", "AssignToRole"))));
        assertTrue(ex.getDiagnostics().stream().anyMatch(d -> "MISSING_EMPTY_APPROVER_ROLES".equals(d.code())),
                "a group finds its people by rule: the strategy applies");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private DesignFlowDefinition flowWithApproverSource(Map<String, Object> approverSource) {
        return flowWithConfig(Map.of("approverSources", List.of(approverSource)));
    }

    private DesignFlowDefinition flowWithConfig(Map<String, Object> config) {
        return DesignFlowDefinition.builder()
                .name("Org Approval Test")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder()
                                        .id("approval")
                                        .type(FlowNodeType.APPROVAL)
                                        .label("approval")
                                        .config(config)
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

package io.softa.starter.flow.runtime.descriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.design.FlowNodeDescriptor.Ports;
import io.softa.starter.flow.design.FlowNodeDescriptorProvider;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Provides {@link FlowNodeDescriptor}s for structural (non-executor) node types.
 * Each {@link FlowNodeType} maps 1:1 to a single descriptor.
 * <p>
 * Labels and descriptions are English source text — the framework's i18n
 * translates by the original English string at the REST boundary. Config-schema
 * maps are insertion-ordered so property panels render fields deterministically.
 * <p>
 * Rule: every node type whose runtime handler requires a typed {@code *NodeConfig}
 * must ship a non-empty {@code configSchema}, or the editor cannot render its form.
 */
@Component
public class BuiltinNodeDescriptorProvider implements FlowNodeDescriptorProvider {

    private static final Set<FlowScenario> ALL_SCENARIOS = Set.of(FlowScenario.values());
    private static final Set<FlowScenario> PROCESS_ONLY = Set.of(FlowScenario.PROCESS);

    @Override
    public List<FlowNodeDescriptor> getDescriptors() {
        return List.of(
                structural(FlowNodeType.START,             "Start",            "Flow entry point",                    "play-circle",      0,  Ports.entry(),    ALL_SCENARIOS),
                structural(FlowNodeType.END,               "End",              "Flow terminal point",                 "stop-circle",      1,  Ports.terminal(), ALL_SCENARIOS),
                structural(FlowNodeType.TIMER,             "Timer",            "Scheduled wait node",                 "clock",            5,  null, PROCESS_ONLY, timerConfigSchema()),
                structural(FlowNodeType.APPROVAL,          "Approval",         "Human approval node",                 "check-circle",     10, null, PROCESS_ONLY, approvalConfigSchema()),
                // HUMAN_TASK is intentionally omitted: the runtime has no human-task wait
                // (dedicated wait token, assignee resolution, completion API) and the node is
                // rejected at compile time. Restore this entry (+ humanTaskConfigSchema) from
                // version control when the runtime lands.
                structural(FlowNodeType.INCLUSIVE_GATEWAY, "Inclusive Gateway","Routes to every matching edge",       "git-merge",        21, null, PROCESS_ONLY),
                structural(FlowNodeType.PARALLEL_FORK,     "Parallel Fork",    "Forks into parallel branches (branches execute serially, in order)", "git-fork", 22, null, PROCESS_ONLY),
                structural(FlowNodeType.PARALLEL_JOIN,     "Parallel Join",    "Waits for all forked branches",       "git-pull-request", 23, null, PROCESS_ONLY),
                structural(FlowNodeType.SCRIPT,            "Script",           "AviatorScript expression node",       "terminal",         30, null, ALL_SCENARIOS, scriptConfigSchema()),
                // FOR_EACH is intentionally omitted: its runtime child-node iteration is not yet
                // implemented and it is rejected at compile time. Restore this entry
                // (+ forEachConfigSchema) from version control when the runtime lands.
                structural(FlowNodeType.SUBFLOW,           "Subflow",          "Calls another flow (synchronous-only: the subflow must not wait on approvals or timers)", "layers", 32, null, PROCESS_ONLY, subflowConfigSchema()),
                structural(FlowNodeType.RETURN_VALUE,      "Return Value",     "Returns named values from the flow",  "corner-down-left", 33, null, ALL_SCENARIOS, returnValueConfigSchema())
                // GENERATE_FILE is deliberately NOT listed here: GenerateFileTaskExecutor
                // (conditional on DocumentTemplateService) contributes its descriptor, and a
                // second entry here would trip the registry's duplicate-type boot check.
        );
    }

    private static FlowNodeDescriptor structural(FlowNodeType type, String label, String description,
                                                 String icon, int sortOrder, Ports ports,
                                                 Set<FlowScenario> scenarios) {
        return structural(type, label, description, icon, sortOrder, ports, scenarios, Map.of());
    }

    private static FlowNodeDescriptor structural(FlowNodeType type, String label, String description,
                                                 String icon, int sortOrder, Ports ports,
                                                 Set<FlowScenario> scenarios, Map<String, Object> configSchema) {
        return FlowNodeDescriptor.of(type, label, description, icon, sortOrder, ports,
                configSchema, Map.of(), scenarios, true);
    }

    /** Insertion-ordered map builder — property panels render fields in this order. */
    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> timerConfigSchema() {
        return ordered(
                "durationSeconds",    ordered("type", "number",     "label", "Wait duration (seconds)"),
                "cronExpression",     ordered("type", "string",     "label", "Cron expression"),
                "deadlineExpression", ordered("type", "expression", "label", "Deadline expression (resolves to a timestamp)")
        );
    }

    private static Map<String, Object> approvalConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        // ── Approver resolution ─────────────────────────────────────────────
        schema.put("approvers",     ordered("type", "userPicker",     "label", "Approvers (static)", "multiple", true));
        schema.put("approverSource", ordered("type", "approverSource", "label", "Approvers (dynamic)",
                "options", List.of(
                        ordered("type", "VariableList", "label", "Variable list",
                                "fields", ordered("variable", ordered("type", "string", "label", "Variable name", "required", true))),
                        ordered("type", "Expression", "label", "Expression",
                                "fields", ordered("expression", ordered("type", "expression", "label", "Expression", "required", true))),
                        ordered("type", "InitiatorManager", "label", "Initiator's manager (variable)"),
                        ordered("type", "Role", "label", "Role (variable)",
                                "fields", ordered("roleCode", ordered("type", "string", "label", "Role code", "required", true))),
                        ordered("type", "Supervisor", "label", "Supervisor",
                                "fields", ordered("level", ordered("type", "number", "label", "Level", "default", 1, "min", 1))),
                        ordered("type", "DeptLeader", "label", "Department leader",
                                "fields", ordered("deptId", ordered("type", "deptPicker", "label", "Department (blank = initiator's)"))),
                        ordered("type", "RoleQuery", "label", "Role query",
                                "fields", ordered(
                                        "roleIds", ordered("type", "rolePicker", "label", "Roles", "multiple", true, "required", true),
                                        "deptScope", ordered("type", "enum", "label", "Department scope",
                                                "options", List.of("ALL", "INITIATOR_DEPT", "SPECIFIC_DEPT"), "default", "ALL"),
                                        "deptId", ordered("type", "deptPicker", "label", "Department",
                                                "depends", Map.of("deptScope", "SPECIFIC_DEPT")))),
                        ordered("type", "Position", "label", "Position",
                                "fields", ordered("positionIds", ordered("type", "positionPicker", "label", "Positions",
                                        "multiple", true, "required", true))),
                        ordered("type", "Department", "label", "Department members",
                                "fields", ordered(
                                        "deptIds", ordered("type", "deptPicker", "label", "Departments", "multiple", true),
                                        "deptScope", ordered("type", "enum", "label", "Department scope",
                                                "options", List.of("SPECIFIC", "INITIATOR_DEPT"), "default", "SPECIFIC"),
                                        "includeSubDepts", ordered("type", "boolean", "label", "Include sub-departments", "default", false)))
                )));
        schema.put("emptyApproverStrategy", ordered("type", "enum", "label", "Empty-approver strategy",
                "options", List.of("ERROR", "AUTO_APPROVE", "SKIP"), "default", "ERROR"));
        // ── Vote thresholds ──────────────────────────────────────────────────
        schema.put("approvalMode", ordered("type", "enum", "label", "Approval mode",
                "options", List.of("ANY_ONE", "UNANIMOUS", "MIN_COUNT", "PERCENTAGE"), "default", "ANY_ONE"));
        schema.put("minCount",     ordered("type", "number", "label", "Minimum approvals", "depends", Map.of("approvalMode", "MIN_COUNT")));
        schema.put("percentage",   ordered("type", "number", "label", "Approval percentage (0-100)", "depends", Map.of("approvalMode", "PERCENTAGE")));
        schema.put("rejectMode",   ordered("type", "enum", "label", "Reject mode",
                "options", List.of("ANY_ONE", "UNANIMOUS", "MIN_COUNT", "PERCENTAGE"), "default", "ANY_ONE"));
        schema.put("rejectMinCount",   ordered("type", "number", "label", "Minimum rejections", "depends", Map.of("rejectMode", "MIN_COUNT")));
        schema.put("rejectPercentage", ordered("type", "number", "label", "Rejection percentage (0-100)", "depends", Map.of("rejectMode", "PERCENTAGE")));
        // ── Timeout ──────────────────────────────────────────────────────────
        schema.put("timeout", ordered("type", "approvalTimeout", "label", "Timeout configuration"));
        // ── Return policy ────────────────────────────────────────────────────
        schema.put("returnEnabled",      ordered("type", "boolean", "label", "Allow return"));
        schema.put("returnTarget",       ordered("type", "enum", "label", "Return target",
                "options", List.of("INITIATOR", "PREVIOUS_APPROVAL", "SPECIFIC_NODE"),
                "depends", Map.of("returnEnabled", true)));
        schema.put("returnTargetNodeId", ordered("type", "nodeId", "label", "Target node",
                "depends", Map.of("returnTarget", "SPECIFIC_NODE")));
        // ── Form field permissions ────────────────────────────────────────────
        schema.put("formPermissions", ordered("type", "fieldPermissionMap", "label", "Field permissions"));
        return schema;
    }

    private static Map<String, Object> scriptConfigSchema() {
        return ordered(
                "expression",     ordered("type", "expression", "label", "Expression"),
                "outputVariable", ordered("type", "string",     "label", "Output variable")
        );
    }

    private static Map<String, Object> subflowConfigSchema() {
        return ordered(
                "subflowDesignId", ordered("type", "flowPicker",  "label", "Subflow", "required", true),
                "inputMapping",    ordered("type", "keyValueMap", "label", "Input mapping"),
                "outputVariable",  ordered("type", "string",      "label", "Output variable"),
                "outputMapping",   ordered("type", "keyValueMap", "label", "Output mapping")
        );
    }

    private static Map<String, Object> returnValueConfigSchema() {
        return ordered(
                "outputExpressions", ordered("type", "keyValueMap", "label", "Output expressions", "required", true)
        );
    }
}

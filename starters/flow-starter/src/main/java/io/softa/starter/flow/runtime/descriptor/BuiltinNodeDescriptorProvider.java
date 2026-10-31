package io.softa.starter.flow.runtime.descriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.design.FlowNodeDescriptor.Ports;
import io.softa.starter.flow.design.FlowNodeDescriptorProvider;
import io.softa.starter.flow.enums.ApproverDeptScope;
import io.softa.starter.flow.enums.ApproverRelativeTo;
import io.softa.starter.flow.enums.FlowNodeType;

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

    /** What the designer shows for each {@link ApproverRelativeTo}. */
    private static final Map<String, String> RELATIVE_TO_LABELS = Map.of(
            "SUBJECT", "The employee the record is about",
            "INITIATOR", "The submitter");

    /** What the designer shows for each {@link ApproverDeptScope}. */
    private static final Map<String, String> DEPT_SCOPE_LABELS = Map.of(
            "ALL", "Any department",
            "SUBJECT_DEPT", "Department of the employee the record is about",
            "INITIATOR_DEPT", "Submitter's department",
            "SPECIFIC_DEPT", "A specific department",
            "VARIABLE_DEPT", "Department held by a variable");

    @Override
    public List<FlowNodeDescriptor> getDescriptors() {
        return List.of(
                structural(FlowNodeType.START,             "Start",            "Flow entry point",                    "circle-play",      0,  Ports.entry()),
                structural(FlowNodeType.END,               "End",              "Flow terminal point",                 "circle-stop",      1,  Ports.terminal()),
                structural(FlowNodeType.TIMER,             "Timer",            "Scheduled wait node",                 "clock",            5,  null, timerConfigSchema()),
                structural(FlowNodeType.APPROVAL,          "Approval",         "Human approval node",                 "circle-check",     10, null, approvalConfigSchema()),
                // HUMAN_TASK is intentionally omitted: the runtime has no human-task wait
                // (dedicated wait token, assignee resolution, completion API) and the node is
                // rejected at compile time. Restore this entry (+ humanTaskConfigSchema) from
                // version control when the runtime lands.
                structural(FlowNodeType.INCLUSIVE_GATEWAY, "Inclusive Gateway","Routes to every matching edge",       "git-merge",        21, null),
                structural(FlowNodeType.PARALLEL_FORK,     "Parallel Fork",    "Forks into parallel branches (branches execute serially, in order)", "git-fork", 22, null),
                structural(FlowNodeType.PARALLEL_JOIN,     "Parallel Join",    "Waits for all forked branches",       "git-pull-request", 23, null),
                structural(FlowNodeType.SCRIPT,            "Script",           "AviatorScript expression node",       "terminal",         30, null, scriptConfigSchema()),
                // FOR_EACH is intentionally omitted: its runtime child-node iteration is not yet
                // implemented and it is rejected at compile time. Restore this entry
                // (+ forEachConfigSchema) from version control when the runtime lands.
                structural(FlowNodeType.SUBFLOW,           "Subflow",          "Calls a flow with a Subflow trigger (synchronous-only: it must not wait on timers or async tasks)", "layers", 32, null, subflowConfigSchema()),
                structural(FlowNodeType.RETURN_VALUE,      "Return Value",     "Returns named values from the flow",  "corner-down-left", 33, null, returnValueConfigSchema())
                // GENERATE_FILE is deliberately NOT listed here: GenerateFileTaskExecutor
                // (conditional on DocumentTemplateService) contributes its descriptor, and a
                // second entry here would trip the registry's duplicate-type boot check.
        );
    }

    private static FlowNodeDescriptor structural(FlowNodeType type, String label, String description,
                                                 String icon, int sortOrder, Ports ports) {
        return structural(type, label, description, icon, sortOrder, ports, Map.of());
    }

    private static FlowNodeDescriptor structural(FlowNodeType type, String label, String description,
                                                 String icon, int sortOrder, Ports ports,
                                                 Map<String, Object> configSchema) {
        return FlowNodeDescriptor.of(type, label, description, icon, sortOrder, ports,
                configSchema, Map.of(), type.getScenarios(), true);
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
                "cronExpression",     ordered("type", "cron",       "label", "Cron schedule"),
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
                                "fields", ordered("variable", ordered("type", "variableRef", "label", "Variable", "required", true))),
                        ordered("type", "Expression", "label", "Expression",
                                "fields", ordered("expression", ordered("type", "expression", "label", "Expression", "required", true))),
                        ordered("type", "InitiatorManager", "label", "Initiator's manager (variable)"),
                        // Keys into the caller's role-approver map, which a built-in role's code names; another
                        // key may be typed.
                        ordered("type", "Role", "label", "Role (variable)",
                                "fields", ordered("roleCode", ordered("type", "rolePicker", "label", "Role", "required", true,
                                        "valueField", "code", "allowCustom", true))),
                        // Org-relative sources start from the record's subject — the employee it is about — unless
                        // they say the initiator, so a request filed on someone's behalf goes to their approvers.
                        ordered("type", "Supervisor", "label", "Supervisor",
                                "fields", ordered(
                                        "relativeTo", ordered("type", "enum", "label", "Supervisor of",
                                                "options", enumNames(ApproverRelativeTo.values()), "default", "SUBJECT",
                                                "optionLabels", RELATIVE_TO_LABELS),
                                        "level", ordered("type", "number", "label", "Level", "default", 1, "min", 1))),
                        ordered("type", "DeptLeader", "label", "Department leader",
                                "fields", deptScopeFields(false, "SUBJECT_DEPT")),
                        ordered("type", "DeptHrbp", "label", "Department HRBP",
                                "fields", deptScopeFields(false, "SUBJECT_DEPT")),
                        ordered("type", "RoleQuery", "label", "Role query",
                                "fields", concat(ordered(
                                        // Either list, or both. Ids reach every role, the tenant's own included; codes reach
                                        // the built-in roles (admin-created ones have none) and are the same in every tenant,
                                        // so a flow seeded to many tenants names roles by code.
                                        "roleIds", ordered("type", "rolePicker", "label", "Roles", "multiple", true),
                                        "roleCodes", ordered("type", "rolePicker", "label", "Built-in roles", "multiple", true,
                                                "valueField", "code")),
                                        deptScopeFields(false, "ALL"))),
                        ordered("type", "Position", "label", "Position",
                                "fields", ordered("positionIds", ordered("type", "positionPicker", "label", "Positions",
                                        "multiple", true, "required", true))),
                        ordered("type", "Department", "label", "Department members",
                                "fields", concat(deptScopeFields(true, "SPECIFIC_DEPT"),
                                        ordered("includeSubDepts", ordered("type", "boolean", "label", "Include sub-departments",
                                                "default", false))))
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
        // ── Return policy: a return goes to the initiator and ends the instance ──
        schema.put("returnEnabled",      ordered("type", "boolean", "label", "Allow return to initiator"));
        // ── Form field permissions ────────────────────────────────────────────
        schema.put("formPermissions", ordered("type", "fieldPermissionMap", "label", "Field permissions"));
        return schema;
    }

    /**
     * The department a department-based source works on: {@code deptScope} — {@code ALL} offered only
     * when {@code defaultScope} is it (a role query) — with the department it names when specific (one,
     * or several for {@code multipleDepts}) and the variable that holds it otherwise.
     */
    private static Map<String, Object> deptScopeFields(boolean multipleDepts, String defaultScope) {
        List<String> scopes = Stream.of(ApproverDeptScope.values())
                .filter(scope -> scope != ApproverDeptScope.ALL || "ALL".equals(defaultScope))
                .map(Enum::name)
                .toList();
        return ordered(
                "deptScope", ordered("type", "enum", "label", "Department", "options", scopes, "default", defaultScope,
                        "optionLabels", DEPT_SCOPE_LABELS),
                multipleDepts ? "deptIds" : "deptId", ordered("type", "deptPicker", "label", multipleDepts ? "Departments" : "Department",
                        "multiple", multipleDepts, "depends", Map.of("deptScope", "SPECIFIC_DEPT")),
                "deptVariable", ordered("type", "variableRef", "label", "Variable holding the department id",
                        "depends", Map.of("deptScope", "VARIABLE_DEPT")));
    }


    /** {@code fields}, then {@code more}, in that order. */
    private static Map<String, Object> concat(Map<String, Object> fields, Map<String, Object> more) {
        Map<String, Object> merged = new LinkedHashMap<>(fields);
        merged.putAll(more);
        return merged;
    }

    private static List<String> enumNames(Enum<?>[] values) {
        return Stream.of(values).map(Enum::name).toList();
    }

    private static Map<String, Object> scriptConfigSchema() {
        return ordered(
                "expression",     ordered("type", "expression", "label", "Expression"),
                "outputVariable", ordered("type", "string",     "label", "Output variable")
        );
    }

    private static Map<String, Object> subflowConfigSchema() {
        return ordered(
                "subflowId", ordered("type", "flowPicker",  "label", "Subflow", "required", true),
                "inputMapping",    ordered("type", "keyValueMap", "label", "Input mapping", "valueType", "template"),
                "outputVariable",  ordered("type", "string",      "label", "Output variable"),
                "outputMapping",   ordered("type", "keyValueMap", "label", "Output mapping", "valueType", "template")
        );
    }

    private static Map<String, Object> returnValueConfigSchema() {
        return ordered(
                "outputExpressions", ordered("type", "keyValueMap", "label", "Output expressions", "required", true,
                        "valueType", "expression")
        );
    }
}

package io.softa.starter.flow.runtime.descriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.design.FlowNodeDescriptor.Ports;
import io.softa.starter.flow.enums.ApproverDeptScope;
import io.softa.starter.flow.enums.ApproverRelativeTo;
import io.softa.starter.flow.enums.ApproverSourceType;
import io.softa.starter.flow.enums.EmptyApproverStrategy;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.VoteThresholdMode;

/**
 * The {@link FlowNodeDescriptor}s of the structural (non-executor) node types; the task nodes' come from their
 * executors ({@link FlowNodeDescriptorRegistry}). Each {@link FlowNodeType} maps 1:1 to a single descriptor.
 * <p>
 * Labels and descriptions are English source text — the framework's i18n
 * translates by the original English string at the REST boundary. Config-schema
 * maps are insertion-ordered so property panels render fields deterministically.
 * <p>
 * Rule: every node type whose runtime handler requires a typed {@code *NodeConfig}
 * must ship a non-empty {@code configSchema}, or the editor cannot render its form.
 */
@Component
public class BuiltinNodeDescriptorProvider {

    /**
     * {@code appliesWhen}: the field applies only to a step that may have several approvers — several groups, or one
     * naming several people or finding a source not marked {@code findsOne} ({@code ApprovalNodeConfig}).
     */
    static final String SEVERAL_APPROVERS = "severalApprovers";
    /** {@code appliesWhen}: the field applies only to a step a group of which finds its people by rule. */
    static final String APPROVERS_FOUND_BY_RULE = "approversFoundByRule";

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

    public List<FlowNodeDescriptor> getDescriptors() {
        return List.of(
                structural(FlowNodeType.START,             "Start",            "Flow entry point",                    "circle-play",      0,  Ports.entry()),
                structural(FlowNodeType.END,               "End",              "Flow terminal point",                 "circle-stop",      1,  Ports.terminal()),
                structural(FlowNodeType.APPROVAL,          "Approval",         "Human approval node",                 "circle-check",     10, null, approvalConfigSchema(),
                        // A new step starts with one group on the first option, specific people, waiting for them to
                        // be picked.
                        Map.of("approverSources", List.of(Map.of("type", ApproverSourceType.USERS.getType())))),
                structural(FlowNodeType.INCLUSIVE_GATEWAY, "Inclusive Gateway","Takes every edge whose condition holds, else the edge without a condition", "git-merge", 21, null),
                structural(FlowNodeType.PARALLEL_FORK,     "Parallel Fork",    "Forks into parallel branches (branches execute serially, in order)", "git-fork", 22, null),
                structural(FlowNodeType.PARALLEL_JOIN,     "Join",             "Waits for the branches of a parallel fork, or those an inclusive gateway took, then goes on once", "git-pull-request", 23, null),
                structural(FlowNodeType.SCRIPT,            "Script",           "AviatorScript expression node",       "terminal",         30, null, scriptConfigSchema()),
                structural(FlowNodeType.SUBFLOW,           "Subflow",          "Calls a flow with a Subflow trigger (synchronous-only: it must not wait on async tasks)", "layers", 32, null, subflowConfigSchema()),
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
        return structural(type, label, description, icon, sortOrder, ports, configSchema, Map.of());
    }

    private static FlowNodeDescriptor structural(FlowNodeType type, String label, String description,
                                                 String icon, int sortOrder, Ports ports,
                                                 Map<String, Object> configSchema, Map<String, Object> defaultConfig) {
        return FlowNodeDescriptor.of(type, label, description, icon, sortOrder, ports,
                configSchema, defaultConfig, type.getScenarios());
    }

    /** Insertion-ordered map builder — property panels render fields in this order. */
    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> approvalConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        // ── Approver resolution ─────────────────────────────────────────────
        // One or more groups, making one pool the approval mode counts over. Each group is one select: specific
        // people first, then the sources that find them in the organization when the step is reached — one person,
        // then the holders of a role, a position or a department. A CC rule names its one group of recipients from
        // the same options. An option says what the designer needs to tell which settings apply (`appliesWhen`):
        // where specific people are named (`names`), and that a source finds one person at most (`findsOne`).
        schema.put("approverSources", ordered("type", "approverSources", "label", "Approvers", "required", true,
                "options", withFindsOne(List.of(
                        ordered("type", ApproverSourceType.USERS.getType(), "label", "Specific people",
                                "names", ApproverSourceType.USER_IDS,
                                "fields", ordered(ApproverSourceType.USER_IDS, ordered("type", "userPicker", "label", "People",
                                        "multiple", true, "required", true))),
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
                        ordered("type", "RoleQuery", "label", "Role",
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
                ))));
        // ── Vote thresholds ──────────────────────────────────────────────────
        // Counted over the pool the step's groups make — offered only for a step that may have several approvers: one
        // with one approver at most passes on that one's approval whatever it holds (ApprovalNodeConfig).
        String anyOne = VoteThresholdMode.ANY_ONE.getType();
        String unanimous = VoteThresholdMode.UNANIMOUS.getType();
        String minCount = VoteThresholdMode.MIN_COUNT.getType();
        String percentage = VoteThresholdMode.PERCENTAGE.getType();
        schema.put("approvalMode", ordered("type", "enum", "label", "Approval mode",
                "options", voteThresholdModes(), "default", anyOne,
                "optionLabels", ordered(anyOne, "Any one", unanimous, "Everyone", minCount, "Minimum number",
                        percentage, "Percentage"),
                "optionDescriptions", ordered(
                        anyOne, "One approval passes the step, from anyone its groups find.",
                        unanimous, "Everyone its groups find must approve; someone two groups find approves once.",
                        minCount, "This many approvals pass the step, from anyone its groups find.",
                        percentage, "This share of everyone its groups find must approve."),
                "appliesWhen", SEVERAL_APPROVERS));
        schema.put("minCount", ordered("type", "number", "label", "Minimum approvals",
                "depends", Map.of("approvalMode", minCount), "appliesWhen", SEVERAL_APPROVERS));
        schema.put("percentage", ordered("type", "number", "label", "Approval percentage (0-100)",
                "depends", Map.of("approvalMode", percentage), "appliesWhen", SEVERAL_APPROVERS));
        // ── Nobody found ─────────────────────────────────────────────────────
        // These three are read into enums through Jackson, which takes each one's JSON value and nothing else: offer
        // that value, not the constant name (a chosen constant name made the whole config unreadable).
        // Offered only for a step that finds approvers by rule: one that only names them stops and says why when none
        // of them can act (ApprovalNodeConfig). What a step that finds nobody does is said for the source it finds
        // people with (hintBy: the help by the field, by the type of the step's one group; a step of several groups
        // finds nobody only when none of them finds anyone, said by `otherwise`).
        String error = EmptyApproverStrategy.ERROR.getType();
        String autoApprove = EmptyApproverStrategy.AUTO_APPROVE.getType();
        String toRole = EmptyApproverStrategy.ASSIGN_TO_ROLE.getType();
        schema.put("emptyApproverStrategy", ordered("type", "enum", "label", "If nobody is found",
                "options", List.of(error, autoApprove, toRole),
                "optionLabels", ordered(error, "Stop and say why", autoApprove, "Approve automatically",
                        toRole, "Assign to roles"),
                "optionDescriptions", ordered(
                        error, "A submission is refused with the reason. After an approval the instance fails, and its "
                                + "submitter and the administrators are told.",
                        autoApprove, "The step counts as approved; the history shows it was approved automatically.",
                        toRole, "Anyone holding the roles below decides instead."),
                "hintBy", ordered("field", "approverSources",
                        "hints", ordered(
                                "Supervisor", "Applies when the employee has no manager at this level, or none above "
                                        + "who can approve.",
                                "DeptLeader", "Applies when the department and those above it have no leader who can "
                                        + "approve.",
                                "DeptHrbp", "Applies when the department and those above it have no HR business "
                                        + "partner who can approve.",
                                "RoleQuery", "Applies when nobody who can approve holds these roles.",
                                "Position", "Applies when nobody who can approve holds these positions.",
                                "Department", "Applies when these departments have nobody who can approve."),
                        "otherwise", "Applies when the step finds nobody who can approve."),
                "default", error,
                "appliesWhen", APPROVERS_FOUND_BY_RULE));
        // Who a step that finds nobody goes to under AssignToRole, as a role query names roles: ids reach the tenant's
        // own roles, codes the built-in ones, the same in every tenant.
        schema.put("emptyApproverRoleIds", ordered("type", "rolePicker", "label", "Assign to roles", "multiple", true,
                "depends", Map.of("emptyApproverStrategy", toRole), "appliesWhen", APPROVERS_FOUND_BY_RULE));
        schema.put("emptyApproverRoleCodes", ordered("type", "rolePicker", "label", "Assign to built-in roles",
                "multiple", true, "valueField", "code", "depends", Map.of("emptyApproverStrategy", toRole),
                "appliesWhen", APPROVERS_FOUND_BY_RULE));
        // ── Form field permissions ────────────────────────────────────────────
        schema.put("formPermissions", ordered("type", "fieldPermissionMap", "label", "Field permissions"));
        return schema;
    }

    /** {@code options}, each a source that finds one person at most marked so ({@code findsOne}). */
    private static List<Map<String, Object>> withFindsOne(List<Map<String, Object>> options) {
        options.forEach(option -> {
            ApproverSourceType source = ApproverSourceType.lookup(option.get("type"));
            if (source != null && source.isFindsOne()) {
                option.put("findsOne", true);
            }
        });
        return options;
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

    /** The constant names: for the enums read with {@code valueOf}, which have no JSON value of their own. */
    private static List<String> enumNames(Enum<?>[] values) {
        return Stream.of(values).map(Enum::name).toList();
    }

    /** The vote-threshold modes by their JSON values, the form an approval node's config is read in. */
    private static List<String> voteThresholdModes() {
        return Stream.of(VoteThresholdMode.values()).map(VoteThresholdMode::getType).toList();
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

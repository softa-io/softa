package io.softa.starter.flow.compiler.validation;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.*;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;

/**
 * Validates approval node configs: mode parsing, approver constraints and deadlock detection. A step has no timeout
 * of its own (its flow's {@code remindAfterHours} reminds its approvers), nor a return switch (its flow's
 * {@code returnEnabled} says whether its approvers may return the approval).
 */
public class ApprovalConfigValidator implements FlowValidator {

    private static final String APPROVAL_TYPE = FlowNodeType.APPROVAL.getType();

    private static final String BLANK_APPROVER           = "BLANK_APPROVER";
    private static final String DUPLICATE_APPROVER       = "DUPLICATE_APPROVER";
    private static final String MISSING_APPROVERS         = "MISSING_APPROVERS";
    private static final String INVALID_MIN_COUNT         = "INVALID_MIN_COUNT";
    private static final String INVALID_PERCENTAGE         = "INVALID_PERCENTAGE";
    private static final String UNKNOWN_APPROVER_SOURCE_TYPE = "UNKNOWN_APPROVER_SOURCE_TYPE";
    private static final String INVALID_SUPERVISOR_LEVEL     = "INVALID_SUPERVISOR_LEVEL";
    private static final String MISSING_ROLE_IDS             = "MISSING_ROLE_IDS";
    private static final String MISSING_EMPTY_APPROVER_ROLES = "MISSING_EMPTY_APPROVER_ROLES";
    private static final String MISSING_POSITION_IDS         = "MISSING_POSITION_IDS";
    private static final String MISSING_DEPT_IDS             = "MISSING_DEPT_IDS";
    private static final String INVALID_RELATIVE_TO          = "INVALID_RELATIVE_TO";
    private static final String INVALID_DEPT_SCOPE           = "INVALID_DEPT_SCOPE";
    private static final String MISSING_DEPT_VARIABLE        = "MISSING_DEPT_VARIABLE";
    private static final String UNREADABLE_CONFIG            = "UNREADABLE_CONFIG";
    private static final String MISSING_FIELD_PERMISSION     = "MISSING_FIELD_PERMISSION";
    private static final String FORM_PERMISSIONS             = "formPermissions";
    /** Where a step's specific people are: its {@code Users} source's user ids. */
    private static final String USER_IDS_FIELD               = "config.approverSource." + ApproverSourceType.USER_IDS;

    /** A property named in the reference chain of a failed read: {@code ApprovalNodeConfig["approvalMode"]}. */
    private static final Pattern READ_REFERENCE = Pattern.compile("\\[\"([^\"]+)\"]");

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        validateApprovalConfigs(context.nodeMap().values(), d);
        return d;
    }

    private void validateApprovalConfigs(Collection<FlowGraphNode> nodes, List<CompileDiagnostic> d) {
        for (FlowGraphNode node : nodes) {
            if (!FlowNodeType.APPROVAL.equals(node.getType()) || node.getConfig() == null) {
                continue;
            }
            ApprovalNodeConfig cfg = readConfig(node, withPermissionsNamed(node, d), d);
            if (cfg == null) {
                continue;
            }
            validateApproverSource(node.getId(), node.displayName(), cfg, d);
            validateEmptyApproverRoles(node.getId(), node.displayName(), cfg, d);
            validateVoteThresholds(node.getId(), node.displayName(), cfg, d);
        }
    }

    /**
     * Every field a step lists names its permission. A field listed with none — a row saved before its permission
     * was picked — was read as no rule at all, and published as one: the designer believed the field set, the step
     * did not (zingkey-hcm#2406). Refused, so validating says so and publishing waits for it; not read as
     * {@code Readonly}, which shows a step's approvers a sensitive field their own permissions withhold, where a
     * field left unlisted does not. Checked on the config as stored, before it is read — the read takes an empty
     * permission for none — and the fields refused are left out of what the rest of the validation reads.
     */
    private static Map<String, Object> withPermissionsNamed(FlowGraphNode node, List<CompileDiagnostic> d) {
        Map<String, Object> config = node.getConfig();
        if (!(config.get(FORM_PERMISSIONS) instanceof Map<?, ?> permissions)) {
            return config;
        }
        Map<Object, Object> named = new LinkedHashMap<>();
        permissions.forEach((field, permission) -> {
            if (permission == null || permission instanceof String text && !StringUtils.hasText(text)) {
                d.add(CompileDiagnostic.fieldLevel(node.getId(), APPROVAL_TYPE, "config." + FORM_PERMISSIONS,
                        MISSING_FIELD_PERMISSION, "Approval node '" + node.displayName() + "' lists field '" + field
                                + "' without a permission: pick Hidden, Readonly, Editable or Required."));
            } else {
                named.put(field, permission);
            }
        });
        if (named.size() == permissions.size()) {
            return config;
        }
        Map<String, Object> read = new LinkedHashMap<>(config);
        read.put(FORM_PERMISSIONS, named);
        return read;
    }

    /**
     * The node's config, or null when a value in it cannot be read — an option the designer does not offer, such
     * as one saved by a designer that offered constant names — reported against the field it is in, so the
     * validation answers instead of failing whole.
     */
    private static ApprovalNodeConfig readConfig(FlowGraphNode node, Map<String, Object> config,
                                                 List<CompileDiagnostic> d) {
        try {
            return BeanTool.objectToObject(config, ApprovalNodeConfig.class);
        } catch (RuntimeException e) {
            String detail = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            StringJoiner field = new StringJoiner(".", "config.", "").setEmptyValue("");
            Matcher reference = READ_REFERENCE.matcher(detail);
            while (reference.find()) {
                field.add(reference.group(1));
            }
            String where = field.length() == 0 ? "its config" : field.toString();
            String message = "Approval node '" + node.displayName() + "' cannot read " + where + ": " + detail.lines()
                    .findFirst().orElse(detail);
            d.add(field.length() == 0
                    ? CompileDiagnostic.nodeLevel(node.getId(), APPROVAL_TYPE, UNREADABLE_CONFIG, message)
                    : CompileDiagnostic.fieldLevel(node.getId(), APPROVAL_TYPE, field.toString(), UNREADABLE_CONFIG,
                            message));
            return null;
        }
    }

    /** {@code AssignToRole} hands a step that finds nobody to roles: it names at least one, by id or by code. */
    private void validateEmptyApproverRoles(String nodeId, String name, ApprovalNodeConfig cfg, List<CompileDiagnostic> d) {
        if (EmptyApproverStrategy.ASSIGN_TO_ROLE.equals(cfg.getEmptyApproverStrategy())
                && isEmptyCollection(cfg.getEmptyApproverRoleIds()) && isEmptyCollection(cfg.getEmptyApproverRoleCodes())) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.emptyApproverRoleIds",
                    MISSING_EMPTY_APPROVER_ROLES, "Approval node '" + name + "' hands a step that finds nobody to "
                            + "roles (AssignToRole), but names none: pick at least one role."));
        }
    }

    // ── Approver source ─────────────────────────────────────────────────────

    private void validateApproverSource(String nodeId, String name, ApprovalNodeConfig cfg, List<CompileDiagnostic> d) {
        Map<String, Object> source = cfg.getApproverSource();
        if (source == null) {
            return;
        }
        Object typeObj = source.get("type");
        if (typeObj == null) {
            return;
        }
        String type = typeObj.toString();

        // Validate the type is a known ApproverSourceType
        try {
            ApproverSourceType.of(type);
        } catch (IllegalArgumentException e) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.type",
                    UNKNOWN_APPROVER_SOURCE_TYPE,
                    "Approval node '" + name + "' has unknown approverSource type: " + type));
            return;
        }

        switch (type) {
            case "Supervisor" -> {
                Object level = source.get("level");
                if (level != null) {
                    int l = parseIntSafe(level);
                    if (l < 1) {
                        d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.level",
                                INVALID_SUPERVISOR_LEVEL,
                                "Approval node '" + name + "' supervisor level must be >= 1"));
                    }
                }
                try {
                    ApproverRelativeTo.of(source.get("relativeTo"), ApproverRelativeTo.SUBJECT);
                } catch (IllegalArgumentException e) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.relativeTo",
                            INVALID_RELATIVE_TO, "Approval node '" + name + "' supervisor relativeTo must be one of "
                                    + Arrays.toString(ApproverRelativeTo.values())));
                }
            }
            case "DeptLeader", "DeptHrbp" -> validateDeptScope(nodeId, name, type, source, ApproverDeptScope.SUBJECT_DEPT, d);
            case "RoleQuery" -> {
                if (isEmptyCollection(source.get("roleCodes")) && isEmptyCollection(source.get("roleIds"))) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.roleCodes",
                            MISSING_ROLE_IDS,
                            "Approval node '" + name + "' RoleQuery requires non-empty roleCodes or roleIds"));
                }
                validateDeptScope(nodeId, name, type, source, ApproverDeptScope.ALL, d);
            }
            case "Position" -> {
                if (isEmptyCollection(source.get("positionIds"))) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.positionIds",
                            MISSING_POSITION_IDS,
                            "Approval node '" + name + "' Position requires non-empty positionIds"));
                }
            }
            case "Department" -> validateDeptScope(nodeId, name, type, source, ApproverDeptScope.SPECIFIC_DEPT, d);
            default -> { /* Users (judged with the vote thresholds), VariableList, Expression, InitiatorManager, Role */ }
        }
    }

    /**
     * A department-based source's {@code deptScope} — {@code fallback} when it names none — and what that
     * scope needs: {@code ALL} only for a role query (whose fallback it is), the department(s) for
     * {@code SPECIFIC_DEPT} ({@code deptIds} for department members, {@code deptId} otherwise), the
     * variable for {@code VARIABLE_DEPT}.
     */
    private static void validateDeptScope(String nodeId, String name, String type, Map<String, Object> source,
                                          ApproverDeptScope fallback, List<CompileDiagnostic> d) {
        ApproverDeptScope scope;
        try {
            scope = ApproverDeptScope.of(source.get("deptScope"), fallback);
        } catch (IllegalArgumentException e) {
            scope = null;
        }
        if (scope == null || (scope == ApproverDeptScope.ALL && fallback != ApproverDeptScope.ALL)) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.deptScope",
                    INVALID_DEPT_SCOPE, "Approval node '" + name + "' " + type + " has an unsupported deptScope: "
                            + source.get("deptScope")));
            return;
        }
        boolean multiple = "Department".equals(type);
        if (scope == ApproverDeptScope.SPECIFIC_DEPT
                && (multiple ? isEmptyCollection(source.get("deptIds")) : !StringUtils.hasText(asText(source.get("deptId"))))) {
            String field = multiple ? "deptIds" : "deptId";
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource." + field,
                    MISSING_DEPT_IDS, "Approval node '" + name + "' " + type + " requires " + field
                            + " for deptScope SPECIFIC_DEPT"));
        }
        if (scope == ApproverDeptScope.VARIABLE_DEPT && !StringUtils.hasText(asText(source.get("deptVariable")))) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.deptVariable",
                    MISSING_DEPT_VARIABLE, "Approval node '" + name + "' " + type
                            + " requires deptVariable for deptScope VARIABLE_DEPT"));
        }
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static int parseIntSafe(Object val) {
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isEmptyCollection(Object val) {
        if (val == null) {
            return true;
        }
        if (val instanceof Collection<?> coll) {
            return coll.isEmpty();
        }
        return false;
    }

    /** A {@code Users} source's user ids as written, blanks included — {@link ApproverSourceType#namedUsers} drops them. */
    private static List<String> userIdsAsWritten(Map<String, Object> source) {
        return source.get(ApproverSourceType.USER_IDS) instanceof Collection<?> ids
                ? ids.stream().map(id -> id == null ? "" : id.toString()).toList()
                : List.of();
    }

    // ── Vote thresholds ────────────────────────────────────────────────────

    private void validateVoteThresholds(String nodeId, String name, ApprovalNodeConfig cfg, List<CompileDiagnostic> d) {
        VoteThresholdMode approvalMode = cfg.getApprovalMode() != null ? cfg.getApprovalMode() : VoteThresholdMode.ANY_ONE;
        Map<String, Object> source = cfg.getApproverSource();
        Object type = source == null ? null : source.get("type");
        boolean users = ApproverSourceType.USERS.getType().equals(type);
        List<String> approvers = users ? userIdsAsWritten(source) : List.of();
        List<String> nonBlankApprovers = approvers.stream().filter(StringUtils::hasText).toList();

        if (approvers.size() != nonBlankApprovers.size()) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, USER_IDS_FIELD, BLANK_APPROVER,
                    "Approval node '" + name + "' names a blank approver"));
        }
        if (nonBlankApprovers.size() != new LinkedHashSet<>(nonBlankApprovers).size()) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, USER_IDS_FIELD, DUPLICATE_APPROVER,
                    "Approval node '" + name + "' names the same approver twice"));
        }

        // A step names who approves it: specific people, or a source that finds them. One that names neither finds
        // nobody on every run — under the default strategy every submission is refused (zingkey-hcm#2450) — so it is
        // not published; the vote thresholds have nothing to count then, and are not judged.
        if (type == null || !StringUtils.hasText(type.toString()) || (users && nonBlankApprovers.isEmpty())) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, users ? USER_IDS_FIELD : "config.approverSource",
                    MISSING_APPROVERS, "Approval node '" + name + "' names no approver: pick people, or a source that "
                            + "finds them (a supervisor, a role, ...)."));
            return;
        }
        // A rejection has no threshold: one approver's rejection rejects the approval, so only the approvals are counted.
        // Only specific people can be counted before the step is reached.
        validateApprovalMode(nodeId, name, approvalMode, nonBlankApprovers, !users,
                cfg.getMinCount(), cfg.getPercentage(), d);
    }

    private void validateApprovalMode(String nodeId, String name, VoteThresholdMode mode,
                                      List<String> approvers, boolean hasDynamic,
                                      Integer minCount, Integer percentage,
                                      List<CompileDiagnostic> d) {
        if (VoteThresholdMode.MIN_COUNT.equals(mode)) {
            if (minCount == null || minCount < 1 || (!hasDynamic && minCount > approvers.size())) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.minCount",
                        INVALID_MIN_COUNT,
                        "Approval node '" + name + "' must define config.minCount between 1 and approver count"));
            }
        }
        if (VoteThresholdMode.PERCENTAGE.equals(mode)) {
            if (percentage == null || percentage < 1 || percentage > 100) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.percentage",
                        INVALID_PERCENTAGE,
                        "Approval node '" + name + "' must define config.percentage between 1 and 100"));
            }
        }
    }
}

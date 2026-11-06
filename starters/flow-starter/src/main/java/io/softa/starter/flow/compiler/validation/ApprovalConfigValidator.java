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
 * Validates approval node configs: their groups of approvers, the approval mode counting over them, the roles a step
 * that finds nobody goes to, and the field permissions. A step has no timeout of its own (its flow's
 * {@code remindAfterHours} reminds its approvers), nor a return switch (its flow's {@code returnEnabled} says whether
 * its approvers may return the approval).
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
    /** Where a step's groups of approvers are; a group's own fields are under its index ({@link #groupField}). */
    private static final String SOURCES_FIELD                = "config.approverSources";

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
            validateApprovers(node.getId(), node.displayName(), cfg, d);
            validateEmptyApproverRoles(node.getId(), node.displayName(), cfg, d);
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

    /**
     * {@code AssignToRole} hands a step that finds nobody to roles: it names at least one, by id or by code. Judged only
     * where the strategy applies — a step that only names its people stops whatever it holds.
     */
    private void validateEmptyApproverRoles(String nodeId, String name, ApprovalNodeConfig cfg, List<CompileDiagnostic> d) {
        if (cfg.findsApproversByRule()
                && EmptyApproverStrategy.ASSIGN_TO_ROLE.equals(cfg.getEmptyApproverStrategy())
                && isEmptyCollection(cfg.getEmptyApproverRoleIds()) && isEmptyCollection(cfg.getEmptyApproverRoleCodes())) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.emptyApproverRoleIds",
                    MISSING_EMPTY_APPROVER_ROLES, "Approval node '" + name + "' hands a step that finds nobody to "
                            + "roles (AssignToRole), but names none: pick at least one role."));
        }
    }

    // ── Approvers ───────────────────────────────────────────────────────────

    /**
     * A step's groups of approvers, and the approval mode counting over the pool they make. A step names who approves
     * it — at least one group, each specific people or a source that finds them. A step or a group that names nobody
     * finds nobody on every run — under the default strategy every submission is refused (zingkey-hcm#2450) — so it
     * is not published; the vote thresholds have nothing to count then, and are not judged.
     */
    private void validateApprovers(String nodeId, String name, ApprovalNodeConfig cfg, List<CompileDiagnostic> d) {
        List<Map<String, Object>> groups = cfg.getApproverSources() == null ? List.of() : cfg.getApproverSources();
        if (groups.isEmpty()) {
            d.add(missingApprovers(nodeId, "Approval node '" + name + "'", SOURCES_FIELD));
            return;
        }
        Set<String> named = new LinkedHashSet<>();
        boolean dynamic = false;
        boolean complete = true;
        for (int i = 0; i < groups.size(); i++) {
            Map<String, Object> group = groups.get(i) == null ? Map.of() : groups.get(i);
            // A message names the group only when the step has several.
            String who = "Approval node '" + name + "'" + (groups.size() > 1 ? " group " + (i + 1) : "");
            Object type = group.get("type");
            if (type == null || !StringUtils.hasText(type.toString())) {
                d.add(missingApprovers(nodeId, who, groupField(i, null)));
                complete = false;
            } else if (ApproverSourceType.USERS.getType().equals(type)) {
                List<String> people = validateNamedUsers(nodeId, who, i, group, d);
                complete &= !people.isEmpty();
                named.addAll(people);
            } else {
                dynamic = true;
                validateSource(nodeId, who, i, type.toString(), group, d);
            }
        }
        // The mode is judged only where it applies: a step with one approver at most passes on that one's approval.
        if (complete && cfg.mayHaveSeveralApprovers()) {
            // A rejection has no threshold: one approver's rejection rejects the approval, so only the approvals are
            // counted. Only specific people can be counted before the step is reached.
            VoteThresholdMode approvalMode = cfg.getApprovalMode() != null ? cfg.getApprovalMode() : VoteThresholdMode.ANY_ONE;
            validateApprovalMode(nodeId, name, approvalMode, List.copyOf(named), dynamic,
                    cfg.getMinCount(), cfg.getPercentage(), d);
        }
    }

    /** Where group {@code index}'s {@code field} is — the group itself when {@code field} is null. */
    private static String groupField(int index, String field) {
        return SOURCES_FIELD + "." + index + (field == null ? "" : "." + field);
    }

    private static CompileDiagnostic missingApprovers(String nodeId, String who, String field) {
        return CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, field, MISSING_APPROVERS,
                who + " names no approver: pick people, or a source that finds them (a supervisor, a role, ...).");
    }

    /** A {@code Users} group's people, blanks left out; each named once, and at least one. */
    private static List<String> validateNamedUsers(String nodeId, String who, int index, Map<String, Object> group,
                                                   List<CompileDiagnostic> d) {
        String field = groupField(index, ApproverSourceType.USER_IDS);
        List<String> written = userIdsAsWritten(group);
        List<String> people = written.stream().filter(StringUtils::hasText).toList();
        if (written.size() != people.size()) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, field, BLANK_APPROVER, who + " names a blank approver"));
        }
        if (people.size() != new LinkedHashSet<>(people).size()) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, field, DUPLICATE_APPROVER,
                    who + " names the same approver twice"));
        }
        if (people.isEmpty()) {
            d.add(missingApprovers(nodeId, who, field));
        }
        return people;
    }

    /** A group that finds its people when the step is reached: a known source, with what it needs. */
    private void validateSource(String nodeId, String who, int index, String type, Map<String, Object> source,
                                List<CompileDiagnostic> d) {
        try {
            ApproverSourceType.of(type);
        } catch (IllegalArgumentException e) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, groupField(index, null),
                    UNKNOWN_APPROVER_SOURCE_TYPE, who + " has unknown approver source type: " + type));
            return;
        }

        switch (type) {
            case "Supervisor" -> {
                Object level = source.get("level");
                if (level != null) {
                    int l = parseIntSafe(level);
                    if (l < 1) {
                        d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, groupField(index, "level"),
                                INVALID_SUPERVISOR_LEVEL, who + " supervisor level must be >= 1"));
                    }
                }
                try {
                    ApproverRelativeTo.of(source.get("relativeTo"), ApproverRelativeTo.SUBJECT);
                } catch (IllegalArgumentException e) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, groupField(index, "relativeTo"),
                            INVALID_RELATIVE_TO, who + " supervisor relativeTo must be one of "
                                    + Arrays.toString(ApproverRelativeTo.values())));
                }
            }
            case "DeptLeader", "DeptHrbp" ->
                    validateDeptScope(nodeId, who, index, type, source, ApproverDeptScope.SUBJECT_DEPT, d);
            case "RoleQuery" -> {
                if (isEmptyCollection(source.get("roleCodes")) && isEmptyCollection(source.get("roleIds"))) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, groupField(index, "roleCodes"),
                            MISSING_ROLE_IDS, who + " RoleQuery requires non-empty roleCodes or roleIds"));
                }
                validateDeptScope(nodeId, who, index, type, source, ApproverDeptScope.ALL, d);
            }
            case "Position" -> {
                if (isEmptyCollection(source.get("positionIds"))) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, groupField(index, "positionIds"),
                            MISSING_POSITION_IDS, who + " Position requires non-empty positionIds"));
                }
            }
            case "Department" -> validateDeptScope(nodeId, who, index, type, source, ApproverDeptScope.SPECIFIC_DEPT, d);
            default -> { /* VariableList, Expression, InitiatorManager, Role */ }
        }
    }

    /**
     * A department-based source's {@code deptScope} — {@code fallback} when it names none — and what that
     * scope needs: {@code ALL} only for a role query (whose fallback it is), the department(s) for
     * {@code SPECIFIC_DEPT} ({@code deptIds} for department members, {@code deptId} otherwise), the
     * variable for {@code VARIABLE_DEPT}.
     */
    private static void validateDeptScope(String nodeId, String who, int index, String type, Map<String, Object> source,
                                          ApproverDeptScope fallback, List<CompileDiagnostic> d) {
        ApproverDeptScope scope;
        try {
            scope = ApproverDeptScope.of(source.get("deptScope"), fallback);
        } catch (IllegalArgumentException e) {
            scope = null;
        }
        if (scope == null || (scope == ApproverDeptScope.ALL && fallback != ApproverDeptScope.ALL)) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, groupField(index, "deptScope"),
                    INVALID_DEPT_SCOPE, who + " " + type + " has an unsupported deptScope: " + source.get("deptScope")));
            return;
        }
        boolean multiple = "Department".equals(type);
        if (scope == ApproverDeptScope.SPECIFIC_DEPT
                && (multiple ? isEmptyCollection(source.get("deptIds")) : !StringUtils.hasText(asText(source.get("deptId"))))) {
            String field = multiple ? "deptIds" : "deptId";
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, groupField(index, field),
                    MISSING_DEPT_IDS, who + " " + type + " requires " + field + " for deptScope SPECIFIC_DEPT"));
        }
        if (scope == ApproverDeptScope.VARIABLE_DEPT && !StringUtils.hasText(asText(source.get("deptVariable")))) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, groupField(index, "deptVariable"),
                    MISSING_DEPT_VARIABLE, who + " " + type + " requires deptVariable for deptScope VARIABLE_DEPT"));
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

    /**
     * The approval mode over the step's pool: {@code approvers}, the specific people its groups name, each once — a
     * count it cannot outgrow unless another group finds more when the step is reached ({@code hasDynamic}).
     */
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

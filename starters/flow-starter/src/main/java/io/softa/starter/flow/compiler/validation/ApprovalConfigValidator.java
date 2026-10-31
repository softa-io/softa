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
import io.softa.starter.flow.runtime.spi.ApprovalTimeoutConfig;

/**
 * Validates approval node configs: mode parsing, approver constraints, deadlock detection and
 * timeouts. A return always goes to the initiator, so {@code returnEnabled} needs no validation.
 */
public class ApprovalConfigValidator implements FlowValidator {

    private static final String APPROVAL_TYPE = FlowNodeType.APPROVAL.getType();

    private static final String BLANK_APPROVER           = "BLANK_APPROVER";
    private static final String DUPLICATE_APPROVER       = "DUPLICATE_APPROVER";
    private static final String UNANIMOUS_NEEDS_APPROVERS = "UNANIMOUS_NEEDS_APPROVERS";
    private static final String MIN_COUNT_NEEDS_APPROVERS = "MIN_COUNT_NEEDS_APPROVERS";
    private static final String INVALID_MIN_COUNT         = "INVALID_MIN_COUNT";
    private static final String PERCENTAGE_NEEDS_APPROVERS = "PERCENTAGE_NEEDS_APPROVERS";
    private static final String INVALID_PERCENTAGE         = "INVALID_PERCENTAGE";
    private static final String DEADLOCK_THRESHOLD         = "DEADLOCK_THRESHOLD";
    private static final String UNKNOWN_APPROVER_SOURCE_TYPE = "UNKNOWN_APPROVER_SOURCE_TYPE";
    private static final String INVALID_SUPERVISOR_LEVEL     = "INVALID_SUPERVISOR_LEVEL";
    private static final String MISSING_ROLE_IDS             = "MISSING_ROLE_IDS";
    private static final String MISSING_POSITION_IDS         = "MISSING_POSITION_IDS";
    private static final String MISSING_DEPT_IDS             = "MISSING_DEPT_IDS";
    private static final String INVALID_RELATIVE_TO          = "INVALID_RELATIVE_TO";
    private static final String INVALID_DEPT_SCOPE           = "INVALID_DEPT_SCOPE";
    private static final String MISSING_DEPT_VARIABLE        = "MISSING_DEPT_VARIABLE";
    private static final String INVALID_TIMEOUT_HOURS        = "INVALID_TIMEOUT_HOURS";
    private static final String INVALID_REMIND_INTERVAL      = "INVALID_REMIND_INTERVAL";
    private static final String INVALID_MAX_REMINDERS        = "INVALID_MAX_REMINDERS";
    private static final String REMINDERS_OUTSIDE_REMIND     = "REMINDERS_OUTSIDE_REMIND";
    private static final String MISSING_ESCALATE_TARGET      = "MISSING_ESCALATE_TARGET";
    private static final String UNREADABLE_CONFIG            = "UNREADABLE_CONFIG";

    /** A property named in the reference chain of a failed read: {@code ApprovalNodeConfig["timeout"]}. */
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
            ApprovalNodeConfig cfg = readConfig(node, d);
            if (cfg == null) {
                continue;
            }
            validateApproverSource(node.getId(), cfg, d);
            validateVoteThresholds(node.getId(), cfg, d);
            validateTimeout(node.getId(), cfg, d);
        }
    }

    /**
     * The node's config, or null when a value in it cannot be read — an option the designer does not offer, such
     * as one saved by a designer that offered constant names — reported against the field it is in, so the
     * validation answers instead of failing whole.
     */
    private static ApprovalNodeConfig readConfig(FlowGraphNode node, List<CompileDiagnostic> d) {
        try {
            return BeanTool.objectToObject(node.getConfig(), ApprovalNodeConfig.class);
        } catch (RuntimeException e) {
            String detail = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            StringJoiner field = new StringJoiner(".", "config.", "").setEmptyValue("");
            Matcher reference = READ_REFERENCE.matcher(detail);
            while (reference.find()) {
                field.add(reference.group(1));
            }
            String where = field.length() == 0 ? "its config" : field.toString();
            String message = "Approval node '" + node.getId() + "' cannot read " + where + ": " + detail.lines()
                    .findFirst().orElse(detail);
            d.add(field.length() == 0
                    ? CompileDiagnostic.nodeLevel(node.getId(), APPROVAL_TYPE, UNREADABLE_CONFIG, message)
                    : CompileDiagnostic.fieldLevel(node.getId(), APPROVAL_TYPE, field.toString(), UNREADABLE_CONFIG,
                            message));
            return null;
        }
    }

    // ── Timeout ────────────────────────────────────────────────────────────

    /**
     * A deadline ({@code timeoutHours}) and what happens at it. Without a deadline nothing times out and the rest
     * is ignored — the designer hides it then, and keeps what was entered. Reminders belong to the Remind strategy:
     * the designer clears them when another is chosen, so here they are a mistake.
     */
    private void validateTimeout(String nodeId, ApprovalNodeConfig cfg, List<CompileDiagnostic> d) {
        ApprovalTimeoutConfig timeout = cfg.getTimeout();
        if (timeout == null || timeout.getTimeoutHours() == null) {
            return;
        }
        if (timeout.getTimeoutHours() <= 0) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.timeout.timeoutHours",
                    INVALID_TIMEOUT_HOURS,
                    "Approval node '" + nodeId + "' config.timeout.timeoutHours must be > 0"));
        }
        ApprovalTimeoutStrategy strategy = timeout.getTimeoutStrategy() == null
                ? ApprovalTimeoutStrategy.REMIND : timeout.getTimeoutStrategy();
        Integer remindInterval = timeout.getRemindIntervalHours();
        Integer maxReminders = timeout.getMaxRemindTimes();
        if (!ApprovalTimeoutStrategy.REMIND.equals(strategy) && (remindInterval != null || maxReminders != null)) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, remindInterval != null
                            ? "config.timeout.remindIntervalHours" : "config.timeout.maxRemindTimes",
                    REMINDERS_OUTSIDE_REMIND,
                    "Approval node '" + nodeId + "' sets reminders, but only the Remind timeout strategy sends "
                            + "them and it uses " + strategy.getType()));
        }
        if (remindInterval != null && remindInterval < 1) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.timeout.remindIntervalHours",
                    INVALID_REMIND_INTERVAL,
                    "Approval node '" + nodeId + "' config.timeout.remindIntervalHours must be at least 1"));
        }
        if (maxReminders != null && maxReminders < 1) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.timeout.maxRemindTimes",
                    INVALID_MAX_REMINDERS,
                    "Approval node '" + nodeId + "' config.timeout.maxRemindTimes must be at least 1"));
        }
        if (ApprovalTimeoutStrategy.ESCALATE.equals(timeout.getTimeoutStrategy())
                && !StringUtils.hasText(timeout.getEscalateToUserId())) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.timeout.escalateToUserId",
                    MISSING_ESCALATE_TARGET,
                    "Approval node '" + nodeId
                            + "' uses ESCALATE timeout strategy but config.timeout.escalateToUserId is not set"));
        }
    }

    // ── Approver source ─────────────────────────────────────────────────────

    private void validateApproverSource(String nodeId, ApprovalNodeConfig cfg, List<CompileDiagnostic> d) {
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
                    "Approval node '" + nodeId + "' has unknown approverSource type: " + type));
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
                                "Approval node '" + nodeId + "' supervisor level must be >= 1"));
                    }
                }
                try {
                    ApproverRelativeTo.of(source.get("relativeTo"), ApproverRelativeTo.SUBJECT);
                } catch (IllegalArgumentException e) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.relativeTo",
                            INVALID_RELATIVE_TO, "Approval node '" + nodeId + "' supervisor relativeTo must be one of "
                                    + Arrays.toString(ApproverRelativeTo.values())));
                }
            }
            case "DeptLeader", "DeptHrbp" -> validateDeptScope(nodeId, type, source, ApproverDeptScope.SUBJECT_DEPT, d);
            case "RoleQuery" -> {
                if (isEmptyCollection(source.get("roleCodes")) && isEmptyCollection(source.get("roleIds"))) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.roleCodes",
                            MISSING_ROLE_IDS,
                            "Approval node '" + nodeId + "' RoleQuery requires non-empty roleCodes or roleIds"));
                }
                validateDeptScope(nodeId, type, source, ApproverDeptScope.ALL, d);
            }
            case "Position" -> {
                if (isEmptyCollection(source.get("positionIds"))) {
                    d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.positionIds",
                            MISSING_POSITION_IDS,
                            "Approval node '" + nodeId + "' Position requires non-empty positionIds"));
                }
            }
            case "Department" -> validateDeptScope(nodeId, type, source, ApproverDeptScope.SPECIFIC_DEPT, d);
            default -> { /* VariableList, Expression, InitiatorManager, Role: no extra validation */ }
        }
    }

    /**
     * A department-based source's {@code deptScope} — {@code fallback} when it names none — and what that
     * scope needs: {@code ALL} only for a role query (whose fallback it is), the department(s) for
     * {@code SPECIFIC_DEPT} ({@code deptIds} for department members, {@code deptId} otherwise), the
     * variable for {@code VARIABLE_DEPT}.
     */
    private static void validateDeptScope(String nodeId, String type, Map<String, Object> source,
                                          ApproverDeptScope fallback, List<CompileDiagnostic> d) {
        ApproverDeptScope scope;
        try {
            scope = ApproverDeptScope.of(source.get("deptScope"), fallback);
        } catch (IllegalArgumentException e) {
            scope = null;
        }
        if (scope == null || (scope == ApproverDeptScope.ALL && fallback != ApproverDeptScope.ALL)) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.deptScope",
                    INVALID_DEPT_SCOPE, "Approval node '" + nodeId + "' " + type + " has an unsupported deptScope: "
                            + source.get("deptScope")));
            return;
        }
        boolean multiple = "Department".equals(type);
        if (scope == ApproverDeptScope.SPECIFIC_DEPT
                && (multiple ? isEmptyCollection(source.get("deptIds")) : !StringUtils.hasText(asText(source.get("deptId"))))) {
            String field = multiple ? "deptIds" : "deptId";
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource." + field,
                    MISSING_DEPT_IDS, "Approval node '" + nodeId + "' " + type + " requires " + field
                            + " for deptScope SPECIFIC_DEPT"));
        }
        if (scope == ApproverDeptScope.VARIABLE_DEPT && !StringUtils.hasText(asText(source.get("deptVariable")))) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approverSource.deptVariable",
                    MISSING_DEPT_VARIABLE, "Approval node '" + nodeId + "' " + type
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

    // ── Vote thresholds ────────────────────────────────────────────────────

    private void validateVoteThresholds(String nodeId, ApprovalNodeConfig cfg, List<CompileDiagnostic> d) {
        VoteThresholdMode approvalMode = cfg.getApprovalMode() != null ? cfg.getApprovalMode() : VoteThresholdMode.ANY_ONE;
        boolean hasDynamicApproverSource = cfg.getApproverSource() != null;
        List<String> approvers = cfg.getApprovers() != null ? cfg.getApprovers() : List.of();
        List<String> nonBlankApprovers = approvers.stream().filter(StringUtils::hasText).toList();

        if (approvers.size() != nonBlankApprovers.size()) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers", BLANK_APPROVER,
                    "Approval node '" + nodeId + "' contains blank approvers in config.approvers"));
        }
        if (nonBlankApprovers.size() != new LinkedHashSet<>(nonBlankApprovers).size()) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers", DUPLICATE_APPROVER,
                    "Approval node '" + nodeId + "' contains duplicate approvers in config.approvers"));
        }

        validateApprovalMode(nodeId, approvalMode, nonBlankApprovers, hasDynamicApproverSource,
                cfg.getMinCount(), cfg.getPercentage(), d);

        VoteThresholdMode rejectMode = cfg.getRejectMode() != null ? cfg.getRejectMode() : VoteThresholdMode.ANY_ONE;
        validateRejectMode(nodeId, rejectMode, nonBlankApprovers, hasDynamicApproverSource,
                cfg.getRejectMinCount(), cfg.getRejectPercentage(), d);

        int requiredApprovalCount = resolveRequiredCount(approvalMode, nonBlankApprovers.size(),
                cfg.getMinCount(), cfg.getPercentage());
        int requiredRejectCount = resolveRequiredCount(rejectMode, nonBlankApprovers.size(),
                cfg.getRejectMinCount(), cfg.getRejectPercentage());
        if (!nonBlankApprovers.isEmpty() && requiredApprovalCount + requiredRejectCount > nonBlankApprovers.size() + 1) {
            d.add(CompileDiagnostic.nodeLevel(nodeId, APPROVAL_TYPE, DEADLOCK_THRESHOLD,
                    "Approval node '" + nodeId + "' defines approval/reject thresholds that can deadlock"));
        }
    }

    private void validateApprovalMode(String nodeId, VoteThresholdMode mode,
                                      List<String> approvers, boolean hasDynamic,
                                      Integer minCount, Integer percentage,
                                      List<CompileDiagnostic> d) {
        if (VoteThresholdMode.UNANIMOUS.equals(mode) && approvers.isEmpty() && !hasDynamic) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers",
                    UNANIMOUS_NEEDS_APPROVERS,
                    "Approval node '" + nodeId + "' must define config.approvers for Unanimous approval mode"));
        }
        if (VoteThresholdMode.MIN_COUNT.equals(mode)) {
            if (approvers.isEmpty() && !hasDynamic) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers",
                        MIN_COUNT_NEEDS_APPROVERS,
                        "Approval node '" + nodeId + "' must define config.approvers for MinCount approval mode"));
            }
            if (minCount == null || minCount < 1 || (!hasDynamic && minCount > approvers.size())) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.minCount",
                        INVALID_MIN_COUNT,
                        "Approval node '" + nodeId + "' must define config.minCount between 1 and approver count"));
            }
        }
        if (VoteThresholdMode.PERCENTAGE.equals(mode)) {
            if (approvers.isEmpty() && !hasDynamic) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers",
                        PERCENTAGE_NEEDS_APPROVERS,
                        "Approval node '" + nodeId + "' must define config.approvers for Percentage approval mode"));
            }
            if (percentage == null || percentage < 1 || percentage > 100) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.percentage",
                        INVALID_PERCENTAGE,
                        "Approval node '" + nodeId + "' must define config.percentage between 1 and 100"));
            }
        }
    }

    private void validateRejectMode(String nodeId, VoteThresholdMode mode,
                                    List<String> approvers, boolean hasDynamic,
                                    Integer rejectMinCount, Integer rejectPercentage,
                                    List<CompileDiagnostic> d) {
        if (VoteThresholdMode.UNANIMOUS.equals(mode) && approvers.isEmpty() && !hasDynamic) {
            d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers",
                    UNANIMOUS_NEEDS_APPROVERS,
                    "Approval node '" + nodeId + "' must define config.approvers for Unanimous reject mode"));
        }
        if (VoteThresholdMode.MIN_COUNT.equals(mode)) {
            if (approvers.isEmpty() && !hasDynamic) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers",
                        MIN_COUNT_NEEDS_APPROVERS,
                        "Approval node '" + nodeId + "' must define config.approvers for MinCount reject mode"));
            }
            if (rejectMinCount == null || rejectMinCount < 1 || (!hasDynamic && rejectMinCount > approvers.size())) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.rejectMinCount",
                        INVALID_MIN_COUNT,
                        "Approval node '" + nodeId + "' must define config.rejectMinCount between 1 and approver count"));
            }
        }
        if (VoteThresholdMode.PERCENTAGE.equals(mode)) {
            if (approvers.isEmpty() && !hasDynamic) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers",
                        PERCENTAGE_NEEDS_APPROVERS,
                        "Approval node '" + nodeId + "' must define config.approvers for Percentage reject mode"));
            }
            if (rejectPercentage == null || rejectPercentage < 1 || rejectPercentage > 100) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.rejectPercentage",
                        INVALID_PERCENTAGE,
                        "Approval node '" + nodeId + "' must define config.rejectPercentage between 1 and 100"));
            }
        }
    }

    private int resolveRequiredCount(VoteThresholdMode mode, int approverCount,
                                     Integer minCount, Integer percentage) {
        return switch (mode) {
            case ANY_ONE    -> 1;
            case UNANIMOUS  -> Math.max(1, approverCount);
            case MIN_COUNT  -> minCount == null ? 0 : minCount;
            case PERCENTAGE -> percentage == null ? 0 : (int) Math.ceil((approverCount * percentage) / 100.0d);
        };
    }
}

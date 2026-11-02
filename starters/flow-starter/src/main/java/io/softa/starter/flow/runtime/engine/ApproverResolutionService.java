package io.softa.starter.flow.runtime.engine;

import java.lang.reflect.Array;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.ApproverDeptScope;
import io.softa.starter.flow.enums.ApproverRelativeTo;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Resolves approvers and vote thresholds from compiled approval node configuration.
 */
@Component
public class ApproverResolutionService {

    public static final String APPROVER_CONTEXT_KEY = "approverContext";
    public static final String INITIATOR_ID_KEY = "initiatorId";
    public static final String INITIATOR_MANAGER_ID_KEY = "initiatorManagerId";
    public static final String ROLE_APPROVERS_KEY = "roleApprovers";
    /**
     * The start variable a record's approval carries its subject in — the id of the employee the record
     * is about, from its handler's {@code subject()} field. Org-relative sources start from it.
     */
    public static final String SUBJECT_ID_KEY = "_subjectId";

    @Autowired(required = false)
    private OrganizationService organizationService;

    /** Who can act: approvers and CC recipients whose account cannot are dropped, wherever they were found. */
    @Autowired(required = false)
    private ActorAccounts actorAccounts;

    public List<String> resolveApprovers(CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getApprovers() == null) {
            return List.of();
        }
        return cfg.getApprovers().stream()
                .filter(Objects::nonNull)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * The variables approvers are resolved against: the instance's own, with the approver context — the
     * initiator, their manager, the role approvers — the dynamic sources read.
     */
    public static Map<String, Object> resolutionVariables(Map<String, Object> variables, String initiatorId) {
        Map<String, Object> resolved = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        Map<String, Object> approverContext = new LinkedHashMap<>();
        approverContext.put(INITIATOR_ID_KEY, initiatorId);
        approverContext.put(INITIATOR_MANAGER_ID_KEY, resolved.getOrDefault(INITIATOR_MANAGER_ID_KEY, null));
        approverContext.put(ROLE_APPROVERS_KEY, resolved.getOrDefault(ROLE_APPROVERS_KEY, Map.of()));
        resolved.put(APPROVER_CONTEXT_KEY, approverContext);
        return resolved;
    }

    /**
     * People found the way an approval step's approvers are — named users, or a dynamic source in
     * {@code approverSource}'s shape — for a list that is not a step's: a flow's CC rule. Those who cannot
     * act are left out, as a step's approvers are.
     */
    public List<String> resolvePeople(List<String> users, Map<String, Object> source, FlowExecutionState state) {
        CompiledFlowNode asStep = CompiledFlowNode.builder()
                .parsedConfig(ApprovalNodeConfig.builder().approvers(users).approverSource(source).build())
                .build();
        return resolveApprovers(asStep, resolutionVariables(state.getVariables(), state.getInitiatorId()));
    }

    /**
     * The approvers of a step reached with {@code variables}: its named users, or those its dynamic source finds —
     * less the users who cannot act ({@link ActorAccounts}), dropped the way the organization lookups drop a
     * supervisor who left. A step left with nobody follows its empty-approver strategy.
     */
    public List<String> resolveApprovers(CompiledFlowNode node, Map<String, Object> variables) {
        List<String> approvers = resolveCandidates(node, variables);
        return actorAccounts == null || approvers.isEmpty() ? approvers : actorAccounts.usable(approvers);
    }

    private List<String> resolveCandidates(CompiledFlowNode node, Map<String, Object> variables) {
        if (!hasDynamicApproverSource(node)) {
            return resolveApprovers(node);
        }
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getApproverSource() == null) {
            return resolveApprovers(node);
        }
        Map<String, Object> source = cfg.getApproverSource();
        String type = asString(source.get("type"));
        if (!StringUtils.hasText(type)) {
            return resolveApprovers(node);
        }

        Map<String, Object> resolutionVariables = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        Object resolved = switch (type) {
            case "VariableList" -> lookupValue(resolutionVariables, asString(source.get("variable")));
            case "Expression" -> resolveExpression(resolutionVariables, asString(source.get("expression")));
            case "InitiatorManager" -> lookupValue(resolutionVariables, INITIATOR_MANAGER_ID_KEY);
            case "Role" -> resolveRoleApprovers(resolutionVariables, asString(source.get("roleCode")));
            case "Supervisor" -> resolveSupervisor(resolutionVariables, source);
            case "DeptLeader" -> resolveDeptLeader(resolutionVariables, source);
            case "DeptHrbp" -> resolveDeptHrbp(resolutionVariables, source);
            case "RoleQuery" -> resolveRoleQuery(resolutionVariables, source);
            case "Position" -> resolvePosition(source);
            case "Department" -> resolveDepartment(resolutionVariables, source);
            default -> null;
        };
        return normalizeApprovers(resolved);
    }

    /**
     * Who a step that found nobody goes to under {@code AssignToRole}: the holders of its empty-approver roles
     * ({@code emptyApproverRoleIds}, {@code emptyApproverRoleCodes}) who can act, found as a role query finds them —
     * every company's — less the submitter and the employee the record is about. Otherwise the step would go back to
     * them, and pass as their own: HR submitting their own leave with no manager would approve it themselves. Empty
     * when the node names no role, or nobody is left.
     *
     * @param variables   the instance's variables
     * @param initiatorId the submitter's account
     */
    public List<String> resolveEmptyApproverRoleHolders(CompiledFlowNode node, Map<String, Object> variables,
                                                        String initiatorId) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || !namesEmptyApproverRoles(cfg)) {
            return List.of();
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "RoleQuery");
        source.put("roleIds", cfg.getEmptyApproverRoleIds());
        source.put("roleCodes", cfg.getEmptyApproverRoleCodes());
        CompiledFlowNode asStep = CompiledFlowNode.builder()
                .parsedConfig(ApprovalNodeConfig.builder().approverSource(source).build())
                .build();
        Map<String, Object> resolution = resolutionVariables(variables, initiatorId);
        Set<String> leftOut = new HashSet<>();
        if (StringUtils.hasText(initiatorId)) {
            leftOut.add(initiatorId);
        }
        Long subject = parseLong(lookupValue(resolution, SUBJECT_ID_KEY));
        Long subjectUser = subject == null || organizationService == null ? null
                : organizationService.getUserIdOfEmployee(subject);
        if (subjectUser != null) {
            leftOut.add(subjectUser.toString());
        }
        return resolveApprovers(asStep, resolution).stream().filter(holder -> !leftOut.contains(holder)).toList();
    }

    /**
     * The names of a step's empty-approver roles, for saying where it went or why it found nobody there: as the
     * organization names them, else the ids and codes as written.
     */
    public List<String> emptyApproverRoleNames(CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || !namesEmptyApproverRoles(cfg)) {
            return List.of();
        }
        List<Long> ids = parseLongList(cfg.getEmptyApproverRoleIds());
        List<String> codes = cfg.getEmptyApproverRoleCodes() == null ? List.of() : cfg.getEmptyApproverRoleCodes();
        List<String> names = organizationService == null ? List.of()
                : organizationService.getRoleNames(ids == null ? List.of() : ids, codes);
        if (!names.isEmpty()) {
            return names;
        }
        List<String> written = new ArrayList<>();
        if (cfg.getEmptyApproverRoleIds() != null) {
            written.addAll(cfg.getEmptyApproverRoleIds());
        }
        written.addAll(codes);
        return written;
    }

    private static boolean namesEmptyApproverRoles(ApprovalNodeConfig cfg) {
        return (cfg.getEmptyApproverRoleIds() != null && !cfg.getEmptyApproverRoleIds().isEmpty())
                || (cfg.getEmptyApproverRoleCodes() != null && !cfg.getEmptyApproverRoleCodes().isEmpty());
    }

    public boolean hasDynamicApproverSource(CompiledFlowNode node) {
        return node != null
                && node.getParsedConfig() instanceof ApprovalNodeConfig cfg
                && cfg.getApproverSource() != null;
    }

    public VoteThresholdMode resolveApprovalMode(CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getApprovalMode() == null) {
            return VoteThresholdMode.ANY_ONE;
        }
        return cfg.getApprovalMode();
    }

    /**
     * The approvals that pass the step: its approval mode's count over {@code approvers}, never more than there are
     * approvers — the people who cannot act were already left out, so a "two of them" step left with one asks that one.
     */
    public int resolveRequiredApprovalCount(CompiledFlowNode node, List<String> approvers) {
        VoteThresholdMode approvalMode = resolveApprovalMode(node);
        ApprovalNodeConfig cfg = node.getParsedConfig() instanceof ApprovalNodeConfig c ? c : null;
        int required = switch (approvalMode) {
            case ANY_ONE -> 1;
            case UNANIMOUS -> approvers.isEmpty() ? 1 : approvers.size();
            case MIN_COUNT -> cfg != null && cfg.getMinCount() != null ? cfg.getMinCount() : 0;
            case PERCENTAGE -> {
                int pct = cfg != null && cfg.getPercentage() != null ? cfg.getPercentage() : 0;
                yield (int) Math.ceil((approvers.size() * pct) / 100.0d);
            }
        };
        return capRequiredApprovals(required, approvers.size());
    }

    /**
     * The approvals a step of {@code approvers} people can need: no more than there are. The one rule every
     * change of a step's approvers applies too — a reassignment that merges two of them, an add-sign, an
     * escalation.
     */
    public static int capRequiredApprovals(int required, int approvers) {
        return approvers > 0 ? Math.min(required, approvers) : required;
    }

    /**
     * The rejections a step of {@code approvers} people can need beside {@code requiredApprovals}: no more than
     * {@code approvers - requiredApprovals + 1}, and at least one. With more, a vote that splits — one approves, one
     * rejects — reaches neither threshold, every approver has voted, and the step waits for ever.
     */
    public static int capRequiredRejections(int required, int approvers, int requiredApprovals) {
        if (approvers <= 0) {
            return required;
        }
        return Math.min(required, Math.max(1, approvers - requiredApprovals + 1));
    }

    public VoteThresholdMode resolveRejectMode(CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getRejectMode() == null) {
            return VoteThresholdMode.ANY_ONE;
        }
        return cfg.getRejectMode();
    }

    /**
     * The rejections that reject the step: its reject mode's count over {@code approvers}, capped against the
     * approvals it needs ({@link #capRequiredRejections}) so that a split vote always decides it.
     */
    public int resolveRequiredRejectCount(CompiledFlowNode node, List<String> approvers) {
        VoteThresholdMode rejectMode = resolveRejectMode(node);
        ApprovalNodeConfig cfg = node.getParsedConfig() instanceof ApprovalNodeConfig c ? c : null;
        int required = switch (rejectMode) {
            case ANY_ONE -> 1;
            case UNANIMOUS -> approvers.isEmpty() ? 1 : approvers.size();
            case MIN_COUNT -> cfg != null && cfg.getRejectMinCount() != null ? cfg.getRejectMinCount() : 0;
            case PERCENTAGE -> {
                int pct = cfg != null && cfg.getRejectPercentage() != null ? cfg.getRejectPercentage() : 0;
                yield (int) Math.ceil((approvers.size() * pct) / 100.0d);
            }
        };
        return capRequiredRejections(required, approvers.size(), resolveRequiredApprovalCount(node, approvers));
    }

    // ── Org-aware resolution methods ────────────────────────────────────

    private Object resolveSupervisor(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        ApproverRelativeTo relativeTo = ApproverRelativeTo.of(source.get("relativeTo"), ApproverRelativeTo.SUBJECT);
        Long employeeId = relativeTo == ApproverRelativeTo.SUBJECT ? subjectEmployee(variables) : initiatorEmployee(variables);
        int level = parseIntOrDefault(source.get("level"), 1);
        ApproverInfo supervisor = organizationService.getSupervisorOfEmployee(employeeId, level);
        return supervisor == null ? null : supervisor.getUserIdAsString();
    }

    private Object resolveDeptLeader(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        return departments(variables, source, ApproverDeptScope.SUBJECT_DEPT).stream()
                .map(organizationService::getDeptLeader)
                .filter(Objects::nonNull)
                .map(ApproverInfo::getUserIdAsString)
                .toList();
    }

    private Object resolveDeptHrbp(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        return departments(variables, source, ApproverDeptScope.SUBJECT_DEPT).stream()
                .map(organizationService::getDeptHrbp)
                .filter(Objects::nonNull)
                .map(ApproverInfo::getUserIdAsString)
                .toList();
    }

    private Object resolveRoleQuery(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        // Holders of the roles named by id, by code, or both. Codes are the same in every tenant, so
        // seeded templates name built-in roles by code; ids reach the tenant's own roles, which have none.
        List<String> roleCodes = parseStringList(source.get("roleCodes"));
        List<Long> roleIds = parseLongList(source.get("roleIds"));
        if ((roleCodes == null || roleCodes.isEmpty()) && (roleIds == null || roleIds.isEmpty())) {
            return null;
        }
        Map<Long, ApproverInfo> holders = new LinkedHashMap<>();
        if (roleIds != null && !roleIds.isEmpty()) {
            organizationService.getUsersByRoleIds(roleIds).forEach(user -> holders.putIfAbsent(user.getUserId(), user));
        }
        if (roleCodes != null && !roleCodes.isEmpty()) {
            organizationService.getUsersByRoleCodes(roleCodes).forEach(user -> holders.putIfAbsent(user.getUserId(), user));
        }
        List<ApproverInfo> users = List.copyOf(holders.values());
        if (ApproverDeptScope.of(source.get("deptScope"), ApproverDeptScope.ALL) != ApproverDeptScope.ALL) {
            // Narrowed to a department that cannot be told: nobody, never every holder.
            List<Long> deptIds = departments(variables, source, ApproverDeptScope.ALL);
            users = users.stream().filter(user -> deptIds.contains(user.getDeptId())).toList();
        }
        return users.stream().map(ApproverInfo::getUserIdAsString).toList();
    }

    private Object resolvePosition(Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        List<Long> positionIds = parseLongList(source.get("positionIds"));
        if (positionIds == null || positionIds.isEmpty()) {
            return null;
        }
        return organizationService.getUsersByPositionIds(positionIds).stream()
                .map(ApproverInfo::getUserIdAsString).toList();
    }

    private Object resolveDepartment(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        List<Long> deptIds = departments(variables, source, ApproverDeptScope.SPECIFIC_DEPT);
        if (deptIds.isEmpty()) {
            return null;
        }
        boolean includeSubDepts = Boolean.TRUE.equals(source.get("includeSubDepts"));
        return organizationService.getUsersByDeptIds(deptIds, includeSubDepts).stream()
                .map(ApproverInfo::getUserIdAsString).toList();
    }

    // ── Org-context helpers ───────────────────────────────────────────────

    /**
     * The departments a department-based source works on, by its {@code deptScope} — {@code fallback}
     * when it names none. Empty when they cannot be told: no subject or initiator department, nothing
     * named, an empty variable.
     */
    private List<Long> departments(Map<String, Object> variables, Map<String, Object> source,
                                   ApproverDeptScope fallback) {
        List<Long> deptIds = switch (ApproverDeptScope.of(source.get("deptScope"), fallback)) {
            case ALL -> List.of();
            case SUBJECT_DEPT -> listOf(organizationService.getDeptIdOfEmployee(subjectEmployee(variables)));
            case INITIATOR_DEPT -> listOf(organizationService.getDeptIdOfEmployee(initiatorEmployee(variables)));
            case SPECIFIC_DEPT -> {
                List<Long> named = parseLongList(source.get("deptIds"));
                yield named != null ? named : listOf(parseLong(source.get("deptId")));
            }
            case VARIABLE_DEPT -> {
                Object value = lookupValue(variables, asString(source.get("deptVariable")));
                List<Long> held = parseLongList(value);
                yield held != null ? held : listOf(parseLong(value));
            }
        };
        return deptIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    /**
     * The record's subject, the employee it is about ({@value #SUBJECT_ID_KEY}). An instance without a
     * record — a debug run — has none, and starts from its initiator instead.
     */
    private Long subjectEmployee(Map<String, Object> variables) {
        Long subjectId = parseLong(lookupValue(variables, SUBJECT_ID_KEY));
        return subjectId != null ? subjectId : initiatorEmployee(variables);
    }

    /** The employee of the initiator's login account. */
    private Long initiatorEmployee(Map<String, Object> variables) {
        Long initiatorId = parseLong(lookupValue(variables, INITIATOR_ID_KEY));
        return initiatorId == null ? null : organizationService.getEmployeeIdOfUser(initiatorId);
    }

    private static List<Long> listOf(Long id) {
        return id == null ? List.of() : List.of(id);
    }

    private Long parseLong(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number n) {
            return n.longValue();
        }
        String s = val.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntOrDefault(Object val, int defaultValue) {
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private List<String> parseStringList(Object val) {
        if (!(val instanceof Collection<?> coll)) {
            return null;
        }
        return coll.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<Long> parseLongList(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Collection<?> coll) {
            return coll.stream()
                    .map(this::parseLong)
                    .filter(Objects::nonNull)
                    .toList();
        }
        return null;
    }

    // ── Existing resolution methods ───────────────────────────────────────

    private Object resolveExpression(Map<String, Object> variables, String expression) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        return ComputeUtils.execute(expression, new LinkedHashMap<>(variables));
    }

    @SuppressWarnings("unchecked")
    private Object resolveRoleApprovers(Map<String, Object> variables, String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return null;
        }
        Object roleApprovers = lookupValue(variables, ROLE_APPROVERS_KEY);
        if (!(roleApprovers instanceof Map<?, ?> map)) {
            return null;
        }
        return ((Map<String, Object>) map).get(roleCode);
    }

    @SuppressWarnings("unchecked")
    private Object lookupValue(Map<String, Object> variables, String key) {
        if (!StringUtils.hasText(key) || variables == null || variables.isEmpty()) {
            return null;
        }
        if (variables.containsKey(key)) {
            return variables.get(key);
        }
        Object approverContext = variables.get(APPROVER_CONTEXT_KEY);
        if (approverContext instanceof Map<?, ?> context && context.containsKey(key)) {
            return ((Map<String, Object>) context).get(key);
        }
        if (!key.contains(".")) {
            return null;
        }

        Object current = variables;
        for (String part : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = ((Map<String, Object>) currentMap).get(part);
        }
        return current;
    }

    private List<String> normalizeApprovers(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }
        if (value.getClass().isArray()) {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                Object item = Array.get(value, i);
                if (item != null && StringUtils.hasText(item.toString()) && !result.contains(item.toString())) {
                    result.add(item.toString());
                }
            }
            return List.copyOf(result);
        }
        String single = value.toString();
        if (!StringUtils.hasText(single)) {
            return List.of();
        }
        return List.of(single);
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}

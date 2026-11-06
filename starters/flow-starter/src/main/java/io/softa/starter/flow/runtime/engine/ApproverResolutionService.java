package io.softa.starter.flow.runtime.engine;

import java.lang.reflect.Array;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.i18n.I18n;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.ApproverDeptScope;
import io.softa.starter.flow.enums.ApproverRelativeTo;
import io.softa.starter.flow.enums.ApproverSourceType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
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
     * People named or found the way an approval step's approvers are — a source in {@code approverSource}'s shape —
     * for a list that is not a step's: a flow's CC rule. Those who cannot act are left out, as a step's approvers are.
     */
    public List<String> resolvePeople(Map<String, Object> source, FlowExecutionState state) {
        CompiledFlowNode asStep = CompiledFlowNode.builder()
                .parsedConfig(ApprovalNodeConfig.builder().approverSource(source).build())
                .build();
        return resolveApprovers(asStep, resolutionVariables(state.getVariables(), state.getInitiatorId()));
    }

    /**
     * The approvers of a step reached with {@code variables}: the users its source names, or those it finds — less
     * the users who cannot act ({@link ActorAccounts}), dropped the way the organization lookups drop a supervisor
     * who left. A step left with nobody follows its empty-approver strategy.
     */
    public List<String> resolveApprovers(CompiledFlowNode node, Map<String, Object> variables) {
        List<String> approvers = resolveCandidates(node, variables);
        return actorAccounts == null || approvers.isEmpty() ? approvers : actorAccounts.usable(approvers);
    }

    /**
     * Why a step reached with {@code variables} found nobody, as a clause of the refusal its submitter takes to whoever
     * sets approvers up — the employee has no manager who can approve, nobody who can holds its roles; null when its
     * source cannot say more than that nobody was found.
     */
    public String whyNobody(CompiledFlowNode node, Map<String, Object> variables) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg)) {
            return null;
        }
        Map<String, Object> source = cfg.getApproverSource();
        String type = source == null ? null : asString(source.get("type"));
        if (!StringUtils.hasText(type)) {
            return I18n.get("it names no approver");
        }
        Map<String, Object> resolutionVariables = variables == null ? Map.of() : variables;
        return switch (type) {
            case "Users" -> ApproverSourceType.namedUsers(source).isEmpty()
                    ? I18n.get("it names no approver") : I18n.get("none of its approvers can approve");
            case "Supervisor" -> organizationService == null ? null : whyNoSupervisor(resolutionVariables, source);
            case "DeptLeader" -> organizationService == null ? null : whyNoDeptHead(resolutionVariables, source,
                    "{0} and the departments above it have no leader who can approve");
            case "DeptHrbp" -> organizationService == null ? null : whyNoDeptHead(resolutionVariables, source,
                    "{0} and the departments above it have no HR business partner who can approve");
            case "RoleQuery" -> I18n.get("nobody who can approve holds its roles");
            case "Position" -> I18n.get("nobody who can approve holds its positions");
            case "Department" -> I18n.get("its departments have nobody who can approve");
            case "VariableList", "Expression" -> I18n.get("its rule names nobody who can approve");
            default -> null;
        };
    }

    private String whyNoSupervisor(Map<String, Object> variables, Map<String, Object> source) {
        ApproverRelativeTo relativeTo = ApproverRelativeTo.of(source.get("relativeTo"), ApproverRelativeTo.SUBJECT);
        Long employeeId = relativeTo == ApproverRelativeTo.SUBJECT ? subjectEmployee(variables) : initiatorEmployee(variables);
        if (employeeId == null) {
            return relativeTo == ApproverRelativeTo.SUBJECT
                    ? I18n.get("the request names no employee")
                    : I18n.get("the submitter has no employee profile");
        }
        String name = organizationService.getEmployeeNames(List.of(employeeId)).get(employeeId);
        String who = StringUtils.hasText(name) ? name : I18n.get("the employee");
        int level = parseIntOrDefault(source.get("level"), 1);
        return level <= 1
                ? I18n.get("{0} has no manager who can approve", who)
                : I18n.get("{0} has no manager {1} levels up who can approve", who, level);
    }

    private String whyNoDeptHead(Map<String, Object> variables, Map<String, Object> source, String message) {
        List<Long> deptIds = departments(variables, source, ApproverDeptScope.SUBJECT_DEPT);
        if (deptIds.isEmpty()) {
            return I18n.get("its department could not be told");
        }
        Collection<String> names = organizationService.getDeptNames(deptIds).values();
        return I18n.get(message, names.isEmpty() ? I18n.get("the department") : String.join(", ", names));
    }

    private List<String> resolveCandidates(CompiledFlowNode node, Map<String, Object> variables) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getApproverSource() == null) {
            return List.of();
        }
        Map<String, Object> source = cfg.getApproverSource();
        String type = asString(source.get("type"));
        if (!StringUtils.hasText(type)) {
            return List.of();
        }

        Map<String, Object> resolutionVariables = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        Object resolved = switch (type) {
            case "Users" -> ApproverSourceType.namedUsers(source);
            case "VariableList" -> lookupValue(resolutionVariables, asString(source.get("variable")));
            case "Expression" -> resolveExpression(node, resolutionVariables, asString(source.get("expression")));
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

    /** Whether the step finds its approvers when it is reached: a source other than the specific people it names. */
    public boolean hasDynamicApproverSource(CompiledFlowNode node) {
        if (node == null || !(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getApproverSource() == null) {
            return false;
        }
        String type = asString(cfg.getApproverSource().get("type"));
        return StringUtils.hasText(type) && !ApproverSourceType.USERS.getType().equals(type);
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
     * change of a step's approvers applies too — a reassignment that merges two of them. A rejection needs no such
     * rule: one rejects the approval.
     */
    public static int capRequiredApprovals(int required, int approvers) {
        return approvers > 0 ? Math.min(required, approvers) : required;
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
     * The record's subject, the employee it is about ({@value #SUBJECT_ID_KEY}). An instance without one
     * starts from its initiator instead.
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

    /**
     * The approvers an expression names. One that fails is the step's configuration at fault, not the server: refused
     * with the step and what went wrong, as a step that finds nobody is — at a submission the submission is, after an
     * approval the instance fails with it — instead of an error nobody can read.
     */
    private Object resolveExpression(CompiledFlowNode node, Map<String, Object> variables, String expression) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        try {
            return ComputeUtils.execute(expression, new LinkedHashMap<>(variables));
        } catch (RuntimeException e) {
            String label = StringUtils.hasText(node.getLabel()) ? node.getLabel() : node.getNodeId();
            String detail = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()).lines()
                    .findFirst().orElse("");
            throw new FlowActionValidationException(I18n.get(
                    "The approver expression of step ''{0}'' failed: {1}. Ask your HR administrator to correct it.",
                    label, detail));
        }
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

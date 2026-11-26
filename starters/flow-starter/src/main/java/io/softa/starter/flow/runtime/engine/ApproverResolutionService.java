package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.i18n.I18n;
import io.softa.framework.orm.meta.MetaField;
import io.softa.starter.flow.enums.ApproverDeptScope;
import io.softa.starter.flow.enums.ApproverRelativeTo;
import io.softa.starter.flow.enums.ApproverSourceType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.RecordVariables;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.spi.impl.FlowOrgService;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Resolves approvers and vote thresholds from compiled approval node configuration.
 */
@Component
public class ApproverResolutionService {

    /**
     * The start variable a record's approval carries its subject in — the id of the employee the record
     * is about, from its handler's {@code subject()} field. Org-relative sources start from it.
     */
    public static final String SUBJECT_ID_KEY = "_subjectId";

    /**
     * The start variable a record's approval carries its model in, as every triggered start does: what a field a source
     * reads from the record ({@link #recordField}) is looked up on.
     */
    public static final String MODEL_KEY = "_sourceModel";

    /**
     * What a {@code RecordField} source's field may point at: the people it names, an employee — who approves under
     * their login account — or the login account itself.
     */
    public static final List<String> PERSON_MODELS = List.of(FlowOrgService.EMPLOYEE_MODEL, FlowOrgService.ACCOUNT_MODEL);

    /** What a department-based source's {@code VARIABLE_DEPT} field points at: a department. */
    public static final List<String> DEPARTMENT_MODELS = List.of(FlowOrgService.DEPARTMENT_MODEL);

    @Autowired(required = false)
    private OrganizationService organizationService;

    /** Who can act: approvers and CC recipients whose account cannot are dropped, wherever they were found. */
    @Autowired(required = false)
    private ActorAccounts actorAccounts;

    /**
     * People named or found the way a group of an approval step's approvers is — a source in the shape of an
     * {@code approverSources} entry — for a list that is not a step's: a flow's CC rule. Those who cannot act are left
     * out, as a step's approvers are.
     */
    public List<String> resolvePeople(Map<String, Object> source, FlowExecutionState state) {
        return resolveApprovers(asStep(source), state.getVariables(), state.getInitiatorId());
    }

    /** A step whose one group is {@code source}: for a list resolved the way a step's approvers are. */
    private static CompiledFlowNode asStep(Map<String, Object> source) {
        return CompiledFlowNode.builder()
                .parsedConfig(ApprovalNodeConfig.builder()
                        .approverSources(source == null ? List.of() : List.of(source))
                        .build())
                .build();
    }

    /**
     * The approvers of a step reached with {@code variables}, submitted by {@code initiatorId}: everyone its groups
     * name or find, each once — less the users who cannot act ({@link ActorAccounts}), dropped the way the
     * organization lookups drop a supervisor who left. A step left with nobody follows its empty-approver strategy.
     */
    public List<String> resolveApprovers(CompiledFlowNode node, Map<String, Object> variables, String initiatorId) {
        List<String> approvers = resolveCandidates(node, new Resolution(variables, initiatorId));
        return actorAccounts == null || approvers.isEmpty() ? approvers : actorAccounts.usable(approvers);
    }

    /**
     * Why a step reached with {@code variables} found nobody, as a clause of the refusal its submitter takes to whoever
     * sets approvers up — the employee has no manager who can approve, nobody who can holds its roles; each of its
     * groups says why, joined. Null when none can say more than that nobody was found.
     */
    public String whyNobody(CompiledFlowNode node, Map<String, Object> variables, String initiatorId) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg)) {
            return null;
        }
        List<Map<String, Object>> groups = groups(cfg);
        if (groups.stream().noneMatch(group -> StringUtils.hasText(asString(group.get("type"))))) {
            return I18n.get("it names no approver");
        }
        Resolution resolution = new Resolution(variables, initiatorId);
        // The pool is empty only when every group found nobody: each group that can say why does, once.
        Set<String> reasons = new LinkedHashSet<>();
        for (Map<String, Object> group : groups) {
            String reason = whyGroupFoundNobody(group, resolution);
            if (reason != null) {
                reasons.add(reason);
            }
        }
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    /** Why one group found nobody; null when it cannot say more, or picks no source (another group says why). */
    private String whyGroupFoundNobody(Map<String, Object> source, Resolution resolution) {
        String type = asString(source.get("type"));
        if (!StringUtils.hasText(type)) {
            return null;
        }
        return switch (type) {
            case "Users" -> ApproverSourceType.namedUsers(source).isEmpty()
                    ? I18n.get("it names no approver") : I18n.get("none of its approvers can approve");
            case "Supervisor" -> organizationService == null ? null : whyNoSupervisor(resolution, source);
            case "DeptLeader" -> organizationService == null ? null : whyNoDeptHead(resolution, source,
                    "{0} and the departments above it have no leader who can approve");
            case "DeptHrbp" -> organizationService == null ? null : whyNoDeptHead(resolution, source,
                    "{0} and the departments above it have no HR business partner who can approve");
            case "RecordField" -> whyNoRecordPerson(resolution, source);
            case "RoleQuery" -> I18n.get("nobody who can approve holds its roles");
            case "Position" -> I18n.get("nobody who can approve holds its positions");
            case "Department" -> I18n.get("its departments have nobody who can approve");
            default -> null;
        };
    }

    /** Why the person a field of the record names is nobody: the field is empty, or they cannot approve. */
    private String whyNoRecordPerson(Resolution resolution, Map<String, Object> source) {
        MetaField field = recordField(resolution.modelName(), source.get(ApproverSourceType.FIELD), PERSON_MODELS);
        if (field == null) {
            // Published before the field was removed or pointed elsewhere: it reads nobody until the flow changes.
            return I18n.get("the request has no field {0} naming a person", source.get(ApproverSourceType.FIELD));
        }
        Long id = parseLong(RecordVariables.keyOf(resolution.variable(field.getFieldName())));
        if (id == null) {
            return I18n.get("{0} on the request is empty", field.getLabel());
        }
        String name = organizationService == null ? null : personName(field, id);
        return I18n.get("{0}, {1} on the request, cannot approve",
                StringUtils.hasText(name) ? name : I18n.get("the person"), field.getLabel());
    }

    /** The name of the person {@code id} is on {@code field}: an employee's, or an account's. */
    private String personName(MetaField field, Long id) {
        if (FlowOrgService.EMPLOYEE_MODEL.equals(field.getRelatedModel())) {
            return organizationService.getEmployeeNames(List.of(id)).get(id);
        }
        return organizationService.getUsersByIds(List.of(id)).stream()
                .map(ApproverInfo::getUserName).filter(StringUtils::hasText).findFirst().orElse(null);
    }

    private String whyNoSupervisor(Resolution resolution, Map<String, Object> source) {
        ApproverRelativeTo relativeTo = ApproverRelativeTo.of(source.get("relativeTo"), ApproverRelativeTo.SUBJECT);
        Long employeeId = relativeTo == ApproverRelativeTo.SUBJECT ? subjectEmployee(resolution) : initiatorEmployee(resolution);
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

    private String whyNoDeptHead(Resolution resolution, Map<String, Object> source, String message) {
        List<Long> deptIds = departments(resolution, source, ApproverDeptScope.SUBJECT_DEPT);
        if (deptIds.isEmpty()) {
            return I18n.get("its department could not be told");
        }
        Collection<String> names = organizationService.getDeptNames(deptIds).values();
        return I18n.get(message, names.isEmpty() ? I18n.get("the department") : String.join(", ", names));
    }

    private List<String> resolveCandidates(CompiledFlowNode node, Resolution resolution) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg)) {
            return List.of();
        }
        // One pool: everyone the groups name or find, each once, in the groups' order.
        Set<String> pool = new LinkedHashSet<>();
        for (Map<String, Object> group : groups(cfg)) {
            pool.addAll(resolveGroup(group, resolution));
        }
        return List.copyOf(pool);
    }

    /** The users one group names or finds. */
    private List<String> resolveGroup(Map<String, Object> source, Resolution resolution) {
        String type = asString(source.get("type"));
        if (!StringUtils.hasText(type)) {
            return List.of();
        }
        Object resolved = switch (type) {
            case "Users" -> ApproverSourceType.namedUsers(source);
            case "Supervisor" -> resolveSupervisor(resolution, source);
            case "DeptLeader" -> resolveDeptLeader(resolution, source);
            case "DeptHrbp" -> resolveDeptHrbp(resolution, source);
            case "RecordField" -> resolveRecordField(resolution, source);
            case "RoleQuery" -> resolveRoleQuery(resolution, source);
            case "Position" -> resolvePosition(source);
            case "Department" -> resolveDepartment(resolution, source);
            default -> null;
        };
        return normalizeApprovers(resolved);
    }

    /**
     * Who a step that found nobody goes to under {@code AssignToRole}: the holders of its empty-approver roles
     * ({@code emptyApproverRoleIds}, {@code emptyApproverRoleCodes}) who can act, found as a role query finds them —
     * every company's. A holder who submitted the request, or whom it is about, stays among them like any other
     * approver: the flow's {@code selfApproval} decides what their own step does, as at every step. Empty when the
     * node names no role, or nobody holds them.
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
        return resolveApprovers(asStep(source), variables, initiatorId);
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

    /** A step's groups of approvers, in order; none when it names none. */
    private static List<Map<String, Object>> groups(ApprovalNodeConfig cfg) {
        return cfg.getApproverSources() == null ? List.of()
                : cfg.getApproverSources().stream().filter(Objects::nonNull).toList();
    }

    /**
     * The step's vote threshold — {@code AnyOne} when it holds none, or when it has one approver at most
     * ({@link ApprovalNodeConfig#mayHaveSeveralApprovers}): that one's approval passes it, whatever mode it holds.
     */
    public VoteThresholdMode resolveVoteThreshold(CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getVoteThreshold() == null
                || !cfg.mayHaveSeveralApprovers()) {
            return VoteThresholdMode.ANY_ONE;
        }
        return cfg.getVoteThreshold();
    }

    /**
     * The approvals that pass the step: its vote threshold's count over {@code approvers}, never more than there are
     * approvers — the people who cannot act were already left out, so a "two of them" step left with one asks that one.
     */
    public int resolveRequiredApprovalCount(CompiledFlowNode node, List<String> approvers) {
        VoteThresholdMode voteThreshold = resolveVoteThreshold(node);
        ApprovalNodeConfig cfg = node.getParsedConfig() instanceof ApprovalNodeConfig c ? c : null;
        int required = switch (voteThreshold) {
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

    private Object resolveSupervisor(Resolution resolution, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        ApproverRelativeTo relativeTo = ApproverRelativeTo.of(source.get("relativeTo"), ApproverRelativeTo.SUBJECT);
        Long employeeId = relativeTo == ApproverRelativeTo.SUBJECT ? subjectEmployee(resolution) : initiatorEmployee(resolution);
        int level = parseIntOrDefault(source.get("level"), 1);
        ApproverInfo supervisor = organizationService.getSupervisorOfEmployee(employeeId, level);
        return supervisor == null ? null : supervisor.getUserIdAsString();
    }

    private Object resolveDeptLeader(Resolution resolution, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        return departments(resolution, source, ApproverDeptScope.SUBJECT_DEPT).stream()
                .map(organizationService::getDeptLeader)
                .filter(Objects::nonNull)
                .map(ApproverInfo::getUserIdAsString)
                .toList();
    }

    private Object resolveDeptHrbp(Resolution resolution, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        return departments(resolution, source, ApproverDeptScope.SUBJECT_DEPT).stream()
                .map(organizationService::getDeptHrbp)
                .filter(Objects::nonNull)
                .map(ApproverInfo::getUserIdAsString)
                .toList();
    }

    /**
     * The person the source's field names, as the record held it at submission — or after an approver's edit: an
     * employee by their login account, an account as it is. Nobody when the field is empty, or no longer one that
     * names a person. One who cannot act is left out with the rest ({@link ActorAccounts}); nobody takes it up for
     * them, as with the people a step names.
     */
    private Object resolveRecordField(Resolution resolution, Map<String, Object> source) {
        MetaField field = recordField(resolution.modelName(), source.get(ApproverSourceType.FIELD), PERSON_MODELS);
        Long id = field == null ? null : parseLong(RecordVariables.keyOf(resolution.variable(field.getFieldName())));
        if (id == null) {
            return null;
        }
        if (FlowOrgService.ACCOUNT_MODEL.equals(field.getRelatedModel())) {
            return id.toString();
        }
        if (organizationService == null) {
            return null;
        }
        Long userId = organizationService.getUserIdOfEmployee(id);
        return userId == null ? null : userId.toString();
    }

    /**
     * The field of {@code modelName} a source reads one of {@code models} from — a {@code RecordField} source's person,
     * a {@code VARIABLE_DEPT} scope's department: a many-to-one or one-to-one field of the record itself that its
     * approvals route on ({@link RecordVariables#routedRelations}), pointing at one of {@code models}. Not through a
     * relation: the people and departments the record's relations lead to are the organization's, found by the
     * sources that walk it — a supervisor, a department's leader. Null when {@code fieldName} names no such field, or
     * {@code modelName} no model.
     */
    public static @Nullable MetaField recordField(@Nullable String modelName, @Nullable Object fieldName,
                                                  Collection<String> models) {
        if (!StringUtils.hasText(modelName) || fieldName == null || !StringUtils.hasText(fieldName.toString())) {
            return null;
        }
        String name = fieldName.toString().trim();
        return RecordVariables.routedRelations(modelName).stream()
                .filter(field -> field.getFieldName().equals(name) && models.contains(field.getRelatedModel()))
                .findFirst().orElse(null);
    }

    private Object resolveRoleQuery(Resolution resolution, Map<String, Object> source) {
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
            List<Long> deptIds = departments(resolution, source, ApproverDeptScope.ALL);
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

    private Object resolveDepartment(Resolution resolution, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        List<Long> deptIds = departments(resolution, source, ApproverDeptScope.SPECIFIC_DEPT);
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
     * named, an empty field.
     */
    private List<Long> departments(Resolution resolution, Map<String, Object> source, ApproverDeptScope fallback) {
        List<Long> deptIds = switch (ApproverDeptScope.of(source.get("deptScope"), fallback)) {
            case ALL -> List.of();
            case SUBJECT_DEPT -> listOf(organizationService.getDeptIdOfEmployee(subjectEmployee(resolution)));
            case INITIATOR_DEPT -> listOf(organizationService.getDeptIdOfEmployee(initiatorEmployee(resolution)));
            case SPECIFIC_DEPT -> {
                List<Long> named = parseLongList(source.get("deptIds"));
                yield named != null ? named : listOf(parseLong(source.get("deptId")));
            }
            // The department a field of the record names, as the record held it at submission (recordField).
            case VARIABLE_DEPT -> listOf(parseLong(RecordVariables.keyOf(
                    resolution.variable(asString(source.get("deptVariable"))))));
        };
        return deptIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    /**
     * The record's subject, the employee it is about ({@value #SUBJECT_ID_KEY}). An instance without one
     * starts from its initiator instead.
     */
    private Long subjectEmployee(Resolution resolution) {
        Long subjectId = parseLong(resolution.variable(SUBJECT_ID_KEY));
        return subjectId != null ? subjectId : initiatorEmployee(resolution);
    }

    /** The employee of the initiator's login account. */
    private Long initiatorEmployee(Resolution resolution) {
        Long initiatorId = parseLong(resolution.initiatorId());
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

    /** What a step's approvers are found from: the instance's variables and its submitter's account. */
    private record Resolution(Map<String, Object> variables, String initiatorId) {

        /** The variable {@code key} names — a path a rule reads is a key of its own — or null. */
        Object variable(String key) {
            return variables == null || !StringUtils.hasText(key) ? null : variables.get(key);
        }

        /** The model of the record the instance approves ({@link #MODEL_KEY}); null for an instance without one. */
        String modelName() {
            Object model = variable(MODEL_KEY);
            return model == null ? null : model.toString();
        }
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

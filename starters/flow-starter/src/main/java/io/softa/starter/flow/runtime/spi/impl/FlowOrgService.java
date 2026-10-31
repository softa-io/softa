package io.softa.starter.flow.runtime.spi.impl;

import java.util.*;
import java.util.function.Supplier;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;

/**
 * Default {@link OrganizationService}: reads the organization through the metadata-driven
 * {@link ModelService}, by the HR conventions permission-starter reads — {@code Employee},
 * {@code Department}, and user-starter's {@code UserRoleRel}, {@code Role} and {@code UserAccount}. Model and field
 * names can be overridden through {@link OrganizationServiceProperties}.
 *
 * <p>Approvers are login accounts that sign in. An employee takes part through the account on
 * {@code Employee.userId}; an employee without an account counts as nobody, and so does one whose
 * account does not sign in — not yet invited, invited but not accepted, frozen, or ended because they
 * left ({@code approverAccountStatuses}) — so a step resolving only to such employees is empty and
 * follows its empty-approver strategy. A role holder is an account, and approves even without an
 * employee profile, provided it signs in. Supervisors, department leaders and HRBPs are stored as
 * employee ids and translated to their accounts; a department without a leader (or HRBP) who can
 * approve defers to its parent's. Looking users up by id, to name them, takes every account.
 *
 * <p>Every read skips row-scope and field checks, the tenant still applying: who approves a
 * submission must not depend on what the submitter may see.
 */
public class FlowOrgService implements OrganizationService {

    private final ModelService<Long> modelService;
    private final OrganizationServiceProperties props;

    @SuppressWarnings("unchecked")
    public FlowOrgService(ModelService<?> modelService, OrganizationServiceProperties props) {
        this.modelService = (ModelService<Long>) modelService;
        this.props = props;
    }

    @Override
    public ApproverInfo getUserById(Long userId) {
        if (userId == null) {
            return null;
        }
        List<ApproverInfo> users = getUsersByIds(List.of(userId));
        return users.isEmpty() ? null : users.getFirst();
    }

    @Override
    public List<ApproverInfo> getUsersByIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        // Every account asked for, whether it can approve now or not: these name people.
        return employees(Filters.of(props.getAccountIdField(), Operator.IN, userIds)).stream()
                .map(this::toUser)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ApproverInfo> getUsersByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        FlexQuery query = new FlexQuery(Filters.of(props.getRoleIdField(), Operator.IN, roleIds));
        query.setFields(List.of(props.getRoleUserIdField()));
        List<Long> holders = read(() -> modelService.searchList(props.getRoleModel(), query)).stream()
                .map(row -> toLong(row.get(props.getRoleUserIdField())))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Set<Long> signingIn = signingIn(holders);
        List<Long> accountIds = holders.stream().filter(signingIn::contains).toList();
        Map<Long, ApproverInfo> employees = new HashMap<>();
        getUsersByIds(accountIds).forEach(user -> employees.put(user.getUserId(), user));
        return accountIds.stream()
                .map(accountId -> employees.getOrDefault(accountId, new ApproverInfo(accountId, null)))
                .toList();
    }

    @Override
    public List<ApproverInfo> getUsersByRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        FlexQuery query = new FlexQuery(Filters.of(props.getRoleCodeField(), Operator.IN, roleCodes));
        query.setFields(List.of(ModelConstant.ID));
        List<Long> roleIds = read(() -> modelService.searchList(props.getRoleDefinitionModel(), query)).stream()
                .map(row -> toLong(row.get(ModelConstant.ID)))
                .filter(Objects::nonNull)
                .toList();
        return getUsersByRoleIds(roleIds);
    }

    @Override
    public List<ApproverInfo> getUsersByDeptIds(List<Long> deptIds, boolean includeSubDepts) {
        if (deptIds == null || deptIds.isEmpty()) {
            return List.of();
        }
        Set<Long> targets = new LinkedHashSet<>(deptIds);
        if (includeSubDepts) {
            deptIds.forEach(deptId -> targets.addAll(getSubDeptIds(deptId)));
        }
        return toApprovers(employees(Filters.of(props.getDeptIdField(), Operator.IN, List.copyOf(targets))));
    }

    @Override
    public List<ApproverInfo> getUsersByPositionIds(List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return List.of();
        }
        return toApprovers(employees(Filters.of(props.getPositionIdField(), Operator.IN, positionIds)));
    }

    @Override
    public Long getEmployeeIdOfUser(Long userId) {
        if (userId == null) {
            return null;
        }
        Map<String, Object> employee = employeeOfAccount(userId);
        return employee == null ? null : toLong(employee.get(ModelConstant.ID));
    }

    @Override
    public Long getUserIdOfEmployee(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        Map<String, Object> employee = employeeById(employeeId);
        return employee == null ? null : toLong(employee.get(props.getAccountIdField()));
    }

    @Override
    public ApproverInfo getSupervisorOfEmployee(Long employeeId, int level) {
        if (employeeId == null || level < 1) {
            return null;
        }
        Map<String, Object> employee = employeeById(employeeId);
        for (int i = 0; i < level && employee != null; i++) {
            Long supervisorId = toLong(employee.get(props.getSupervisorIdField()));
            employee = supervisorId == null ? null : employeeById(supervisorId);
        }
        return employee == null ? null : toApprover(employee);
    }

    @Override
    public ApproverInfo getDeptLeader(Long deptId) {
        return nearestDeptMember(deptId, props.getDeptLeaderIdField());
    }

    @Override
    public ApproverInfo getDeptHrbp(Long deptId) {
        return nearestDeptMember(deptId, props.getDeptHrbpIdField());
    }

    @Override
    public Map<Long, String> getEmployeeNames(Collection<Long> employeeIds) {
        return namesOf(props.getEmployeeModel(), props.getNameField(), employeeIds);
    }

    @Override
    public Map<Long, String> getDeptNames(Collection<Long> deptIds) {
        return namesOf(props.getDepartmentModel(), props.getDeptNameField(), deptIds);
    }

    /** One read of a model's rows by id: their names, the rows without one left out. */
    private Map<Long, String> namesOf(String model, String nameField, Collection<Long> ids) {
        List<Long> distinct = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Map<String, Object> row : read(() -> modelService.getByIds(model, distinct, List.of(ModelConstant.ID, nameField)))) {
            Long id = toLong(row.get(ModelConstant.ID));
            Object name = row.get(nameField);
            if (id != null && name != null && !name.toString().isBlank()) {
                names.put(id, name.toString());
            }
        }
        return names;
    }

    @Override
    public Long getDeptIdOfEmployee(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        Map<String, Object> employee = employeeById(employeeId);
        return employee == null ? null : toLong(employee.get(props.getDeptIdField()));
    }

    @Override
    public List<Long> getSubDeptIds(Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        List<Long> level = List.of(deptId);
        for (int depth = 0; !level.isEmpty() && depth < props.getMaxDeptDepth(); depth++) {
            FlexQuery query = new FlexQuery(Filters.of(props.getParentDeptIdField(), Operator.IN, level));
            query.setFields(List.of(ModelConstant.ID));
            List<Long> children = read(() -> modelService.searchList(props.getDepartmentModel(), query)).stream()
                    .map(row -> toLong(row.get(ModelConstant.ID)))
                    .filter(id -> id != null && !id.equals(deptId) && !result.contains(id))
                    .toList();
            result.addAll(children);
            level = children;
        }
        return Collections.unmodifiableList(result);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * The approver a department names in {@code field} (an employee id), or its nearest ancestor's
     * when it names none, or only an employee without an account that signs in — one who left, say.
     */
    private ApproverInfo nearestDeptMember(Long deptId, String field) {
        Set<Long> visited = new HashSet<>();
        Long current = deptId;
        for (int depth = 0; current != null && depth < props.getMaxDeptDepth() && visited.add(current); depth++) {
            Long id = current;
            Map<String, Object> dept = read(() -> modelService.getById(props.getDepartmentModel(), id,
                    List.of(field, props.getParentDeptIdField()))).orElse(null);
            if (dept == null) {
                return null;
            }
            Long employeeId = toLong(dept.get(field));
            Map<String, Object> employee = employeeId == null ? null : employeeById(employeeId);
            ApproverInfo member = employee == null ? null : toApprover(employee);
            if (member != null) {
                return member;
            }
            current = toLong(dept.get(props.getParentDeptIdField()));
        }
        return null;
    }

    private List<Map<String, Object>> employees(Filters filters) {
        FlexQuery query = new FlexQuery(filters);
        query.setFields(employeeFields());
        return read(() -> modelService.searchList(props.getEmployeeModel(), query));
    }

    private Map<String, Object> employeeOfAccount(Long accountId) {
        List<Map<String, Object>> rows = employees(Filters.of(props.getAccountIdField(), Operator.EQUAL, accountId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> employeeById(Long employeeId) {
        return read(() -> modelService.getById(props.getEmployeeModel(), employeeId, employeeFields())).orElse(null);
    }

    private List<String> employeeFields() {
        return List.of(ModelConstant.ID, props.getAccountIdField(), props.getNameField(), props.getDeptIdField(),
                props.getPositionIdField(), props.getSupervisorIdField());
    }

    /** The employees as approvers: those whose account signs in. */
    private List<ApproverInfo> toApprovers(List<Map<String, Object>> employees) {
        List<ApproverInfo> users = employees.stream().map(this::toUser).filter(Objects::nonNull).toList();
        Set<Long> signingIn = signingIn(users.stream().map(ApproverInfo::getUserId).toList());
        return users.stream().filter(user -> signingIn.contains(user.getUserId())).toList();
    }

    /** The employee as an approver; null without a login account that signs in. */
    private ApproverInfo toApprover(Map<String, Object> employee) {
        ApproverInfo user = toUser(employee);
        return user != null && signingIn(List.of(user.getUserId())).contains(user.getUserId()) ? user : null;
    }

    /**
     * Of these accounts, the ones that sign in: their status is one of {@code approverAccountStatuses}. All of them
     * when the application has no account model to ask.
     */
    private Set<Long> signingIn(Collection<Long> accountIds) {
        if (accountIds.isEmpty() || !ModelManager.existModel(props.getAccountModel())) {
            return new HashSet<>(accountIds);
        }
        FlexQuery query = new FlexQuery(Filters.of(ModelConstant.ID, Operator.IN, List.copyOf(accountIds)));
        query.setFields(List.of(ModelConstant.ID, props.getAccountStatusField()));
        Set<Long> signingIn = new HashSet<>();
        for (Map<String, Object> account : read(() -> modelService.searchList(props.getAccountModel(), query))) {
            Object status = account.get(props.getAccountStatusField());
            if (status != null && props.getApproverAccountStatuses().contains(status.toString())) {
                signingIn.add(toLong(account.get(ModelConstant.ID)));
            }
        }
        return signingIn;
    }

    /** The employee as a user, by their login account; null without one. */
    private ApproverInfo toUser(Map<String, Object> employee) {
        Long accountId = toLong(employee.get(props.getAccountIdField()));
        if (accountId == null) {
            return null;
        }
        Object name = employee.get(props.getNameField());
        return new ApproverInfo(accountId, name == null ? null : name.toString(),
                toLong(employee.get(props.getDeptIdField())), null);
    }

    private static <T> T read(Supplier<T> query) {
        return ContextUtils.withoutPermissionChecks(query);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

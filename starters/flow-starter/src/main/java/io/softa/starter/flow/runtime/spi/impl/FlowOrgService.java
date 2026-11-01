package io.softa.starter.flow.runtime.spi.impl;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.DirectoryDepartment;
import io.softa.starter.flow.runtime.spi.DirectoryPerson;
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
 * approve defers to its parent's. Looking users up by id, to name them, takes every account; which of
 * them cannot act — users a flow or an action names directly — {@link #getUnusableAccounts} tells.
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

    /**
     * The accounts whose status is not one of {@code approverAccountStatuses}, each with its status — null for an
     * account the tenant does not have. None when the application has no account model to ask.
     */
    @Override
    public Map<Long, String> getUnusableAccounts(Collection<Long> userIds) {
        List<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || !ModelManager.existModel(props.getAccountModel())) {
            return Map.of();
        }
        Map<Long, String> statuses = accountStatuses(ids);
        Map<Long, String> unusable = new LinkedHashMap<>();
        for (Long id : ids) {
            String status = statuses.get(id);
            if (!signsIn(status)) {
                unusable.put(id, status);
            }
        }
        return unusable;
    }

    /**
     * Employees with a login account, matched through the employee model's search fields ({@code searchName}), by
     * name. Whether the account signs in is a condition of the read, so a page holds that many people who can act,
     * not fewer; department and position come back named as references, so a search is one read and its references.
     */
    @Override
    public List<DirectoryPerson> searchPeople(String keyword, boolean includeInactive, int limit) {
        Filters filters = Filters.of(props.getAccountIdField(), Operator.IS_SET, null);
        if (StringUtils.hasText(keyword)) {
            filters = Filters.and(filters, Filters.of(ModelConstant.SEARCH_NAME, Operator.CONTAINS, keyword.trim()));
        }
        boolean withStatuses = !includeInactive && ModelManager.existModel(props.getAccountModel());
        if (withStatuses) {
            filters = Filters.and(filters, Filters.of(props.getAccountIdField() + "." + props.getAccountStatusField(),
                    Operator.IN, props.getApproverAccountStatuses()));
        }
        FlexQuery query = new FlexQuery(personFields(), filters, Orders.ofAsc(props.getNameField()));
        query.setConvertType(ConvertType.REFERENCE);
        query.setLimitSize(limit);
        return toPeople(read(() -> modelService.searchList(props.getEmployeeModel(), query)), includeInactive);
    }

    @Override
    public List<DirectoryPerson> getPeople(Collection<Long> userIds) {
        List<Long> ids = distinct(userIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        FlexQuery query = new FlexQuery(personFields(), Filters.of(props.getAccountIdField(), Operator.IN, ids));
        query.setConvertType(ConvertType.REFERENCE);
        Map<Long, DirectoryPerson> people = new HashMap<>();
        for (DirectoryPerson person : toPeople(read(() -> modelService.searchList(props.getEmployeeModel(), query)), true)) {
            people.put(person.id(), person);
        }
        return ids.stream().map(people::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<DirectoryDepartment> searchDepartments(String keyword, int limit) {
        Filters filters = StringUtils.hasText(keyword)
                ? Filters.of(ModelConstant.SEARCH_NAME, Operator.CONTAINS, keyword.trim()) : null;
        FlexQuery query = new FlexQuery(departmentFields(), filters, Orders.ofAsc(props.getDeptNameField()));
        query.setConvertType(ConvertType.REFERENCE);
        query.setLimitSize(limit);
        return read(() -> modelService.searchList(props.getDepartmentModel(), query)).stream()
                .map(this::toDepartment)
                .toList();
    }

    @Override
    public List<DirectoryDepartment> getDepartments(Collection<Long> deptIds) {
        List<Long> ids = distinct(deptIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        FlexQuery query = new FlexQuery(departmentFields(), Filters.of(ModelConstant.ID, Operator.IN, ids));
        query.setConvertType(ConvertType.REFERENCE);
        // A department closed since a flow named it still names it.
        query.getFilterControl().setSkipActiveControl(true);
        Map<Long, DirectoryDepartment> departments = new HashMap<>();
        for (Map<String, Object> row : read(() -> modelService.searchList(props.getDepartmentModel(), query))) {
            DirectoryDepartment department = toDepartment(row);
            if (department.id() != null) {
                departments.put(department.id(), department);
            }
        }
        return ids.stream().map(departments::get).filter(Objects::nonNull).toList();
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
        Set<Long> signingIn = new HashSet<>();
        accountStatuses(accountIds).forEach((id, status) -> {
            if (signsIn(status)) {
                signingIn.add(id);
            }
        });
        return signingIn;
    }

    /** The status of each of these accounts the tenant has, by account id; null for one without a status. */
    private Map<Long, String> accountStatuses(Collection<Long> accountIds) {
        FlexQuery query = new FlexQuery(Filters.of(ModelConstant.ID, Operator.IN, List.copyOf(accountIds)));
        query.setFields(List.of(ModelConstant.ID, props.getAccountStatusField()));
        Map<Long, String> statuses = new HashMap<>();
        for (Map<String, Object> account : read(() -> modelService.searchList(props.getAccountModel(), query))) {
            Long id = toLong(account.get(ModelConstant.ID));
            Object status = account.get(props.getAccountStatusField());
            if (id != null) {
                statuses.put(id, status == null ? null : status.toString());
            }
        }
        return statuses;
    }

    /** Whether an account of this status signs in: it is one of {@code approverAccountStatuses}. */
    private boolean signsIn(String status) {
        return status != null && props.getApproverAccountStatuses().contains(status);
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

    /** What the directory reads of an employee: account, name, department, position, and the code where there is one. */
    private List<String> personFields() {
        List<String> fields = new ArrayList<>(List.of(ModelConstant.ID, props.getAccountIdField(), props.getNameField(),
                props.getDeptIdField(), props.getPositionIdField()));
        if (ModelManager.existField(props.getEmployeeModel(), props.getCodeField())) {
            fields.add(props.getCodeField());
        }
        return fields;
    }

    /** What the directory reads of a department: name, parent, and the code where there is one. */
    private List<String> departmentFields() {
        List<String> fields = new ArrayList<>(List.of(ModelConstant.ID, props.getDeptNameField(),
                props.getParentDeptIdField()));
        if (ModelManager.existField(props.getDepartmentModel(), props.getDeptCodeField())) {
            fields.add(props.getDeptCodeField());
        }
        return fields;
    }

    /**
     * Employees read as references, as the directory shows them: one per account, the first an account appears on.
     * Whether each signs in is asked when {@code askStatuses}; otherwise the read already kept only those who do.
     */
    private List<DirectoryPerson> toPeople(List<Map<String, Object>> employees, boolean askStatuses) {
        Map<Long, Map<String, Object>> byAccount = new LinkedHashMap<>();
        for (Map<String, Object> employee : employees) {
            Long accountId = idOf(employee.get(props.getAccountIdField()));
            if (accountId != null) {
                byAccount.putIfAbsent(accountId, employee);
            }
        }
        Set<Long> signingIn = askStatuses ? signingIn(byAccount.keySet()) : byAccount.keySet();
        List<DirectoryPerson> people = new ArrayList<>(byAccount.size());
        byAccount.forEach((accountId, employee) -> people.add(new DirectoryPerson(accountId,
                textOf(employee.get(props.getNameField())), textOf(employee.get(props.getCodeField())),
                labelOf(employee.get(props.getDeptIdField())), labelOf(employee.get(props.getPositionIdField())),
                signingIn.contains(accountId))));
        return people;
    }

    private DirectoryDepartment toDepartment(Map<String, Object> department) {
        return new DirectoryDepartment(idOf(department.get(ModelConstant.ID)),
                textOf(department.get(props.getDeptNameField())), textOf(department.get(props.getDeptCodeField())),
                labelOf(department.get(props.getParentDeptIdField())));
    }

    private static List<Long> distinct(Collection<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    /** A to-one value's id, whether it was read as a reference or as the bare id. */
    private static Long idOf(Object value) {
        if (value instanceof ModelReference reference) {
            return toLong(reference.getId());
        }
        if (value instanceof Map<?, ?> reference) {
            return toLong(reference.get(ModelConstant.ID));
        }
        return toLong(value);
    }

    /** A to-one value's display name, when it was read as a reference; null otherwise. */
    private static String labelOf(Object value) {
        if (value instanceof ModelReference reference) {
            return textOf(reference.getDisplayName());
        }
        return value instanceof Map<?, ?> reference ? textOf(reference.get(ModelConstant.DISPLAY_NAME)) : null;
    }

    private static String textOf(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
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

package io.softa.starter.flow.runtime.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * SPI for resolving organizational data.
 * <p>
 * Every user id here — taken or returned — is a login account id, the id approvers act under; an
 * employee id is an employee record's own id, the one a supervisor, a department leader or a record's
 * subject is stored as. Lookups relative to someone start from an employee: a record's subject is
 * one already, and a user is bridged through {@link #getEmployeeIdOfUser}. The approver lookups —
 * by role, department, position, supervisor, leader, HRBP — return only users who can approve, whose
 * account signs in; the lookups by user id return every user asked for, to name them. The default
 * {@code FlowOrgService} reads the conventional HR models; applications implement this interface
 * only to integrate a different organization system.
 * </p>
 */
public interface OrganizationService {

    /**
     * Get user info by user ID.
     */
    ApproverInfo getUserById(Long userId);

    /**
     * Get users by user IDs.
     */
    List<ApproverInfo> getUsersByIds(List<Long> userIds);

    /**
     * Get users by role IDs.
     */
    List<ApproverInfo> getUsersByRoleIds(List<Long> roleIds);

    /**
     * Get users by department IDs.
     *
     * @param deptIds          department IDs
     * @param includeSubDepts  whether to include sub-departments
     */
    List<ApproverInfo> getUsersByDeptIds(List<Long> deptIds, boolean includeSubDepts);

    /**
     * Get users by position IDs.
     */
    List<ApproverInfo> getUsersByPositionIds(List<Long> positionIds);

    /**
     * The employee whose login account is {@code userId}; null when the account has none.
     */
    Long getEmployeeIdOfUser(Long userId);

    /**
     * The login account of an employee; null when the employee has none.
     */
    Long getUserIdOfEmployee(Long employeeId);

    /**
     * The supervisor {@code level} levels above an employee, as an approver. One who cannot approve — no account, or
     * one that does not sign in (they left, or were never let in) — passes it on up: the first one above them who can
     * takes it, as a department leader who cannot passes it to the department above. Null when the chain ends before
     * {@code level}, or with nobody above who can. Levels count everyone on the chain: level 2 is the supervisor's
     * supervisor whether or not the supervisor can approve.
     *
     * @param employeeId the employee
     * @param level      1 = direct supervisor, 2 = the supervisor's supervisor, and so on
     */
    ApproverInfo getSupervisorOfEmployee(Long employeeId, int level);

    /**
     * Get the department leader.
     */
    ApproverInfo getDeptLeader(Long deptId);

    /**
     * The department of an employee; null when it has none.
     */
    Long getDeptIdOfEmployee(Long employeeId);

    /**
     * Get sub-department IDs of a department.
     */
    List<Long> getSubDeptIds(Long deptId);

    /**
     * Get users holding any of the roles with these codes. Role codes, unlike role ids, are the
     * same in every tenant, so flow templates name roles by code. Empty unless overridden.
     */
    default List<ApproverInfo> getUsersByRoleCodes(List<String> roleCodes) {
        return List.of();
    }

    /**
     * Get the department's HR business partner, walking up to the nearest department that has
     * one. Null unless overridden.
     */
    default ApproverInfo getDeptHrbp(Long deptId) {
        return null;
    }

    /**
     * The display names of employees, by employee id: what approval lists show a record's subject as.
     * Employees without a name are left out. Empty unless overridden.
     */
    default Map<Long, String> getEmployeeNames(Collection<Long> employeeIds) {
        return Map.of();
    }

    /** The names of departments, by department id. Empty unless overridden. */
    default Map<Long, String> getDeptNames(Collection<Long> deptIds) {
        return Map.of();
    }

    /**
     * Of these login accounts, the ones that cannot act in an approval — whose account does not sign in — each with
     * its account status, or with null when there is no such account. Nobody approves, is CC'd on or is handed a
     * task while their account cannot act. Empty unless overridden: every account can act.
     */
    default Map<Long, String> getUnusableAccounts(Collection<Long> userIds) {
        return Map.of();
    }

    /**
     * The directory as the caller may read it: the people they may pick to hand work to, copy or delegate to —
     * employees whose login account signs in, matched on {@code keyword} (all of them when blank), by name, at most
     * {@code limit}, read with the caller's own permissions, so their roles' scope on the directory decides whom.
     * Always in one order, two people of one name included: a picker shows more by asking again with a larger
     * {@code limit}, and what it already shows should stay where it was. Empty unless overridden.
     */
    default List<DirectoryPerson> searchPeople(String keyword, int limit) {
        return List.of();
    }

    /**
     * The whole directory, for the flow designer and the operations console: every login account matching
     * {@code keyword} (all of them when blank) — employees, and accounts without an employee record by their own name —
     * by name, at most {@code limit}, past the caller's permissions. Only those that sign in unless
     * {@code includeInactive}; each says whether it does (@link DirectoryPerson#active). Always in one order, as for
     * {@link #searchPeople}. Empty unless overridden.
     */
    default List<DirectoryPerson> searchAllPeople(String keyword, boolean includeInactive, int limit) {
        return List.of();
    }

    /**
     * These login accounts as the directory shows them, in the order asked, whether they sign in or not, past the
     * caller's permissions: what names a picked person once the search has moved on. An account without an employee
     * record is left out. Empty unless overridden.
     */
    default List<DirectoryPerson> getPeople(Collection<Long> userIds) {
        return List.of();
    }

    /**
     * The names of these roles — named by id, by code, or both — in the order asked, ids first; a role the tenant does
     * not have is left out. What says which roles a step went to when it found nobody. Empty unless overridden.
     */
    default List<String> getRoleNames(Collection<Long> roleIds, Collection<String> roleCodes) {
        return List.of();
    }

    /**
     * Departments matching {@code keyword} (all of them when blank), open ones only, by name, at most {@code limit}:
     * what a flow can name. Always in one order, as for {@link #searchPeople}. Empty unless overridden.
     */
    default List<DirectoryDepartment> searchDepartments(String keyword, int limit) {
        return List.of();
    }

    /** These departments, closed ones included, in the order asked. Empty unless overridden. */
    default List<DirectoryDepartment> getDepartments(Collection<Long> deptIds) {
        return List.of();
    }
}


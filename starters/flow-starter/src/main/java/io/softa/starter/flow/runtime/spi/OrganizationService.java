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
     * The supervisor {@code level} levels above an employee, as an approver; null when the chain ends
     * before it or that supervisor cannot approve — no account, or one that does not sign in (they left,
     * or were never let in). The chain itself runs on through them: level 2 is still their supervisor.
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
}


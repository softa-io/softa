package io.softa.starter.user.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Scope type for data-range filtering.
 * HR-relative types (SELF / DIRECT_REPORTS / DEPT_SUBTREE / MANAGED_DEPARTMENTS /
 * LEGAL_ENTITY) resolve their anchor from the caller's own employee, read from the
 * request context ({@code ContextHolder.getContext().getEmpInfo()}); some also accept
 * an admin-fixed scopeExpr override (see each below). Pure users (no EmpInfo) degrade
 * to a match-none filter on those types. CUSTOM carries an admin-authored Filters array
 * whose dynamic leaf values use env placeholders (USER_ID / USER_EMP_ID / USER_DEPT_ID /
 * USER_COMP_ID) that FilterUnitParser substitutes from the context at SQL-build time.
 */
@Getter
@AllArgsConstructor
public enum ScopeType {
    ALL("All", "No row restriction"),
    SELF("Self", "Only own record (uses employeeId)"),
    DIRECT_REPORTS("DirectReports", "Direct reports (uses employeeId as manager_id)"),
    DEPT_SUBTREE("DeptSubtree", "Subtree of the caller's own department, or a specific department when scopeExpr.deptId is set"),
    MANAGED_DEPARTMENTS("ManagedDepartments", "Departments managed by user (scopeExpr.deptIds optional)"),
    LEGAL_ENTITY("LegalEntity", "Within a specific legal entity (scopeExpr.legalEntityId optional)"),
    CREATED_BY_SELF("CreatedBySelf", "Rows created by the current user (createdId = current user id; works for pure users too)"),
    CUSTOM("Custom", "Custom filter expression (scopeExpr is a Filters array; env-placeholder values are resolved at SQL time)")
    ;

    @JsonValue
    private final String code;

    private final String description;
}

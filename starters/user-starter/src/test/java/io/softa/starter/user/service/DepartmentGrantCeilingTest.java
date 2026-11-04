package io.softa.starter.user.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.service.RoleGrantCeiling.Grants;
import io.softa.starter.user.service.RoleGrantCeiling.Rule;
import io.softa.starter.user.service.RoleGrantCeiling.Scope;
import io.softa.starter.user.service.RoleGrantCeiling.Violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A role editor bounded by a department grant hands out only departments inside their own — judged on
 * where the departments sit in the tree, not on how the grant is written.
 */
class DepartmentGrantCeilingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TICKET = "Ticket";
    private static final Map<String, String> MODEL_BY_PERMISSION = Map.of("ticket.view", TICKET);
    private static final Predicate<String> NO_COMPANY = model -> false;
    private static final Predicate<String> DEPARTMENT_BOUND = TICKET::equals;

    /** Division /1, its teams /1/5 and /1/5/9, a sibling division /15 that shares a leading digit. */
    private static final Set<String> DIVISION = Set.of("/1");

    @Test
    void anyDepartmentInsideTheCallersSubtreeMayBeGranted() {
        assertThat(check(role(Set.of("/1/5", "/1/5/9")), caller(DIVISION))).isEmpty();
        assertThat(check(role(Set.of("/1")), caller(DIVISION))).isEmpty();
        assertThat(check(role(Set.of()), caller(DIVISION))).isEmpty();
    }

    @Test
    void aDepartmentOutsideTheCallersSubtreeIsRefusedAsADepartmentFault() {
        // /15 starts with the same digit as /1 but is not under it.
        assertThat(check(role(Set.of("/1/5", "/15")), caller(DIVISION)))
                .contains(new Violation(Violation.Kind.DEPARTMENTS, TICKET));
    }

    @Test
    void aGrantedRootThatResolvesToNoDepartmentIsNotWithinTheCaller() {
        // A root gone, disabled past reading, or of another tenant: kept as an unresolved path rather than
        // dropped, which would read as granting nothing and pass every bound.
        String unresolved = RoleGrantCeiling.UNRESOLVED_ROOT + "777";
        assertThat(check(role(Set.of(unresolved)), caller(DIVISION)))
                .contains(new Violation(Violation.Kind.DEPARTMENTS, TICKET));
        assertThat(DepartmentGrants.within(unresolved, "/1")).isFalse();
    }

    @Test
    void anUnboundedRoleIsWiderThanABoundedCaller() {
        assertThat(check(role(null), caller(DIVISION)))
                .contains(new Violation(Violation.Kind.DEPARTMENTS, TICKET));
    }

    @Test
    void aCallerWithoutADepartmentGrantMayGrantAny() {
        assertThat(check(role(Set.of("/15")), caller(null))).isEmpty();
        assertThat(check(role(null), caller(null))).isEmpty();
    }

    @Test
    void aModelTheGrantDoesNotBoundIsNotJudgedByIt() {
        Optional<Violation> v = RoleGrantCeiling.firstViolation(role(null), List.of(caller(DIVISION)),
                MODEL_BY_PERMISSION, Map.of(), NO_COMPANY, model -> false);

        assertThat(v).isEmpty();
    }

    @Test
    void aWiderRuleIsStillARecordsFaultEvenWithinTheDepartments() {
        Grants editor = new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(Set.of("ticket.view")),
                new LinkedHashSet<>(), new LinkedHashMap<>(Map.of(TICKET, scope(new Rule("CREATED_BY_SELF", null)))),
                DIVISION);

        assertThat(check(role(Set.of("/1/5")), editor)).contains(new Violation(Violation.Kind.RECORDS, TICKET));
    }

    // ---- the stored value --------------------------------------------------

    @Test
    void readsTheThreeStates() {
        assertThat(DepartmentGrants.roots(null)).isNull();
        assertThat(DepartmentGrants.roots(json("null"))).isNull();
        assertThat(DepartmentGrants.roots(json("[{\"scopeType\":\"ALL\"}]"))).isNull();
        assertThat(DepartmentGrants.roots(json("[]"))).isEmpty();
        assertThat(DepartmentGrants.roots(json(
                "[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"5\"}},"
                        + "{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":9}}]")))
                .containsExactly(5L, 9L);
    }

    @Test
    void acceptsOnlyWhatTheRuntimeReadsTheSameWay() {
        assertThatCode(() -> DepartmentGrants.validate(json("[]"))).doesNotThrowAnyException();
        assertThatCode(() -> DepartmentGrants.validate(json("[{\"scopeType\":\"ALL\"}]"))).doesNotThrowAnyException();
        assertThatCode(() -> DepartmentGrants.validate(json(
                "[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"5\"}}]"))).doesNotThrowAnyException();

        for (String refused : List.of(
                "{\"scopeType\":\"ALL\"}",
                "[{\"scopeType\":\"DEPT_SUBTREE\"}]",
                "[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"abc\"}}]",
                "[{\"scopeType\":\"MANAGED_DEPARTMENTS\"}]",
                "[{\"scopeType\":\"ALL\"},{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"5\"}}]")) {
            assertThatThrownBy(() -> DepartmentGrants.validate(json(refused)))
                    .as(refused).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void containmentIsOnWholeSegments() {
        assertThat(DepartmentGrants.within("/1/5", "/1")).isTrue();
        assertThat(DepartmentGrants.within("/1", "/1")).isTrue();
        assertThat(DepartmentGrants.within("/15", "/1")).isFalse();
        assertThat(DepartmentGrants.within("/1", "/1/5")).isFalse();
    }

    private static Optional<Violation> check(Grants target, Grants caller) {
        return RoleGrantCeiling.firstViolation(target, List.of(caller), MODEL_BY_PERMISSION, Map.of(),
                NO_COMPANY, DEPARTMENT_BOUND);
    }

    /** A role reading every ticket, bounded to these department paths (null = unbounded). */
    private static Grants role(Set<String> departmentPaths) {
        return new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(Set.of("ticket.view")), new LinkedHashSet<>(),
                new LinkedHashMap<>(Map.of(TICKET, scope(Rule.ALL))),
                departmentPaths == null ? null : new LinkedHashSet<>(departmentPaths));
    }

    private static Grants caller(Set<String> departmentPaths) {
        return role(departmentPaths);
    }

    private static Scope scope(Rule... rules) {
        return new Scope(new LinkedHashSet<>(List.of(rules)), null);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}

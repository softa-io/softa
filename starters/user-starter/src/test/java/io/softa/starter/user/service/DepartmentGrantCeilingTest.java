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
import io.softa.framework.orm.constant.ModelConstant;
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
        assertThatCode(() -> DepartmentGrants.validate(json(
                "[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"5\"}},{\"scopeType\":\"OWN_DEPARTMENT\"},"
                        + "{\"scopeType\":\"MANAGED_DEPARTMENTS\"},{\"scopeType\":\"DIRECT_REPORTS\"}]")))
                .doesNotThrowAnyException();

        for (String refused : List.of(
                "{\"scopeType\":\"ALL\"}",
                "[{\"scopeType\":\"DEPT_SUBTREE\"}]",
                "[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"abc\"}}]",
                "[{\"scopeType\":\"SELF\"}]",
                "[{\"scopeType\":\"ALL\"},{\"scopeType\":\"MANAGED_DEPARTMENTS\"}]",
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

    @Test
    void aConditionWorkedOutForTheHolderTakesACallerReachingEveryDepartment() {
        Grants holderWise = role(Set.of()).withDepartmentConditions(Set.of("MANAGED_DEPARTMENTS"));

        assertThat(check(holderWise, caller(null))).isEmpty();
        assertThat(check(holderWise, caller(DIVISION)))
                .contains(new Violation(Violation.Kind.DEPARTMENT_CONDITIONS, "MANAGED_DEPARTMENTS"));
    }

    @Test
    void anExemptionTakesACallerReachingEveryDepartment() {
        Grants exempt = role(Set.of("/1/5"));
        exempt.departmentExempt().add("EmployeeDirectory");

        assertThat(check(exempt, caller(null))).isEmpty();
        assertThat(check(exempt, caller(DIVISION)))
                .contains(new Violation(Violation.Kind.DEPARTMENTS, "EmployeeDirectory"));
    }

    @Test
    void anExemptionOnTheRowsOfAnActionTakesThatCallerRole() {
        // One caller role reaches every department but holds no action; the role holding it is bounded.
        Grants exempt = role(Set.of("/1/5"));
        exempt.departmentExempt().add(TICKET);
        Grants noAction = new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashMap<>());

        assertThat(RoleGrantCeiling.firstViolation(exempt, List.of(noAction, caller(DIVISION)), MODEL_BY_PERMISSION,
                Map.of(), NO_COMPANY, DEPARTMENT_BOUND))
                .contains(new Violation(Violation.Kind.DEPARTMENTS, TICKET));
    }

    @Test
    void aSelfServiceRoleDoesNotCountAsReachingEveryDepartment() {
        // Everyone holds one and nobody bounds it, so counting it would let any bounded caller pass these on.
        Grants holderWise = role(Set.of()).withDepartmentConditions(Set.of("MANAGED_DEPARTMENTS"));
        Grants exempt = role(Set.of("/1/5"));
        exempt.departmentExempt().add("EmployeeDirectory");
        List<Grants> callers = List.of(selfService(), caller(DIVISION));

        assertThat(RoleGrantCeiling.firstViolation(holderWise, callers, MODEL_BY_PERMISSION, Map.of(), NO_COMPANY,
                DEPARTMENT_BOUND)).contains(new Violation(Violation.Kind.DEPARTMENT_CONDITIONS, "MANAGED_DEPARTMENTS"));
        assertThat(RoleGrantCeiling.firstViolation(exempt, callers, MODEL_BY_PERMISSION, Map.of(), NO_COMPANY,
                DEPARTMENT_BOUND)).contains(new Violation(Violation.Kind.DEPARTMENTS, "EmployeeDirectory"));
    }

    @Test
    void aCallerNotBoundedButReadingOnlySomeDepartmentsDoesNotReachThemAll() {
        Grants holderWise = role(Set.of()).withDepartmentConditions(Set.of("MANAGED_DEPARTMENTS"));
        Grants someDepartments = role(null);
        someDepartments.scopes().put(ModelConstant.DEPARTMENT_MODEL,
                scope(new Rule("DEPT_SUBTREE", null)));

        assertThat(check(holderWise, someDepartments))
                .contains(new Violation(Violation.Kind.DEPARTMENT_CONDITIONS, "MANAGED_DEPARTMENTS"));
    }

    @Test
    void readsTheConditionsOfAGrant() {
        assertThat(DepartmentGrants.holderConditions(json(
                "[{\"scopeType\":\"DEPT_SUBTREE\",\"scopeExpr\":{\"deptId\":\"5\"}},{\"scopeType\":\"DIRECT_REPORTS\"}]")))
                .containsExactly("DIRECT_REPORTS");
        assertThat(DepartmentGrants.holderConditions(null)).isEmpty();
    }

    // ---- rules that reach only the holder -----------------------------------------------------------

    private static final Rule SELF = new Rule("SELF", null);

    /** A role with no department grant and these rules on tickets, holding the ticket action. */
    private static Grants unbounded(Rule... rules) {
        return new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(Set.of("ticket.view")), new LinkedHashSet<>(),
                new LinkedHashMap<>(rules.length == 0 ? Map.of() : Map.of(TICKET, scope(rules))));
    }

    @Test
    void anUnboundedRoleReachingOnlyTheHolderIsNoWiderThanABoundedCaller() {
        assertThat(check(unbounded(SELF), caller(DIVISION))).isEmpty();
    }

    @Test
    void anyOtherRuleOnAnUnboundedRoleStaysWider() {
        Rule custom = new Rule("CUSTOM", json("[\"id\",\">\",0]"));
        Rule managed = new Rule("MANAGED_DEPARTMENTS", null);
        for (Grants wider : List.of(unbounded(Rule.ALL), unbounded(custom), unbounded(SELF, custom),
                unbounded(SELF, Rule.ALL), unbounded(managed), unbounded(SELF, managed), unbounded())) {
            assertThat(check(wider, caller(DIVISION))).as(wider.scopes().toString())
                    .contains(new Violation(Violation.Kind.DEPARTMENTS, TICKET));
        }
    }

    @Test
    void theCallersOwnRulesAreStillCompared() {
        // The department axis steps aside; a caller managing departments does not hold SELF, so it cannot hand it on.
        Grants managing = new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(Set.of("ticket.view")),
                new LinkedHashSet<>(), new LinkedHashMap<>(Map.of(TICKET, scope(new Rule("MANAGED_DEPARTMENTS", null)))),
                DIVISION);

        assertThat(check(unbounded(SELF), managing)).contains(new Violation(Violation.Kind.RECORDS, TICKET));
    }

    @Test
    void aTargetCarryingADepartmentGrantKeepsToIt() {
        Grants named = new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(Set.of("ticket.view")),
                new LinkedHashSet<>(), new LinkedHashMap<>(Map.of(TICKET, scope(SELF))), Set.of("/15"));

        assertThat(check(named, caller(DIVISION))).contains(new Violation(Violation.Kind.DEPARTMENTS, TICKET));
    }

    @Test
    void anExemptionOnTheModelIsNotWithinTheRule() {
        Grants exempt = unbounded(SELF);
        exempt.departmentExempt().add(TICKET);

        assertThat(check(exempt, caller(DIVISION))).isPresent();
    }

    @Test
    void theRuleReachesASensitiveFieldSetTheCallerHoldsOnThoseRows() {
        // A set follows the role's rows: the caller still has to hold the set themselves.
        Map<String, Set<String>> setModels = Map.of("pay", Set.of(TICKET));
        Grants target = unbounded(SELF);
        target.sensitiveFieldSets().add("pay");
        Grants holding = caller(DIVISION);
        holding.sensitiveFieldSets().add("pay");

        assertThat(RoleGrantCeiling.firstViolation(target, List.of(holding), MODEL_BY_PERMISSION, setModels, NO_COMPANY,
                DEPARTMENT_BOUND)).isEmpty();
        assertThat(RoleGrantCeiling.firstViolation(target, List.of(caller(DIVISION)), MODEL_BY_PERMISSION, setModels,
                NO_COMPANY, DEPARTMENT_BOUND)).contains(new Violation(Violation.Kind.FIELD_SET, "pay"));
    }

    @Test
    void aSetAttachedToAModelTheRoleHasNoRuleOnStaysOutOfReach() {
        Grants target = unbounded(SELF);
        target.sensitiveFieldSets().add("pay");
        Grants holding = caller(DIVISION);
        holding.sensitiveFieldSets().add("pay");
        Predicate<String> bothBound = model -> true;

        assertThat(RoleGrantCeiling.firstViolation(target, List.of(holding), MODEL_BY_PERMISSION,
                Map.of("pay", Set.of(TICKET, "TicketDetail")), NO_COMPANY, bothBound))
                .contains(new Violation(Violation.Kind.FIELD_SET, "pay"));
    }

    // ---- the shapes of the built-in roles -----------------------------------------------------------

    private static final String LEAVE = "LeaveRequest";
    private static final String EMPLOYEE = "Employee";
    private static final String DIRECTORY = "EmployeeDirectory";
    private static final String LEAVE_PAGE = "navigation.leave";
    private static final String LEAVE_ACTION = "leave.create";
    private static final Map<String, String> ACTIONS = Map.of(LEAVE_ACTION, LEAVE);
    private static final Predicate<String> ALL_BOUND = model -> true;

    /** Self-service: its own record on every card, a directory reading everyone past the grant, no department grant. */
    private static Grants selfServiceShape(boolean withDirectory) {
        Map<String, Scope> scopes = new LinkedHashMap<>();
        scopes.put(EMPLOYEE, scope(SELF));
        scopes.put(LEAVE, scope(SELF));
        Grants role = new Grants(new LinkedHashSet<>(Set.of(LEAVE_PAGE)), new LinkedHashSet<>(Set.of(LEAVE_ACTION)),
                new LinkedHashSet<>(), scopes);
        if (withDirectory) {
            role.scopes().put(DIRECTORY, scope(Rule.ALL));
            role.departmentExempt().add(DIRECTORY);
        }
        return role;
    }

    /** A manager bounded by department, reading everything within it, with or without the self-service page. */
    private static Grants boundedManager(boolean holdsThePage) {
        Map<String, Scope> scopes = new LinkedHashMap<>();
        scopes.put(EMPLOYEE, scope(Rule.ALL));
        scopes.put(LEAVE, scope(Rule.ALL));
        scopes.put(DIRECTORY, scope(Rule.ALL));
        return new Grants(new LinkedHashSet<>(holdsThePage ? Set.of(LEAVE_PAGE) : Set.of()),
                new LinkedHashSet<>(holdsThePage ? Set.of(LEAVE_ACTION) : Set.of()), new LinkedHashSet<>(), scopes, DIVISION);
    }

    private static Optional<Violation> assign(Grants role, Grants... callers) {
        return RoleGrantCeiling.firstViolation(role.addedTo(role), List.of(callers), ACTIONS, Map.of(), NO_COMPANY, ALL_BOUND);
    }

    @Test
    void assigningSelfServiceStopsAtAPageABoundedManagerLacks() {
        assertThat(assign(selfServiceShape(true), boundedManager(false)))
                .contains(new Violation(Violation.Kind.NAVIGATION, LEAVE_PAGE));
    }

    @Test
    void assigningSelfServiceWithThePageStopsAtTheDirectoryReadingEveryone() {
        // The exemption is not judged on an assignment, but its ALL has no department grant around it.
        assertThat(assign(selfServiceShape(true), boundedManager(true)))
                .contains(new Violation(Violation.Kind.DEPARTMENTS, DIRECTORY));
    }

    @Test
    void assigningSelfServiceWithoutTheDirectoryIsWithinABoundedManagerHoldingThePage() {
        assertThat(assign(selfServiceShape(false), boundedManager(true))).isEmpty();
    }

    @Test
    void aManagerAlsoHoldingSelfServiceMayAssignIt() {
        // One of their roles is unbounded and holds the same rules: nothing here turns on the SELF exception.
        assertThat(assign(selfServiceShape(true), boundedManager(false), selfServiceShape(true))).isEmpty();
    }

    @Test
    void assigningARoleOfCustomRulesAndOpenCataloguesIsRefused() {
        // Reviews reached through a custom rule, plans read by everyone, and the directory: no department grant.
        Map<String, Scope> scopes = new LinkedHashMap<>();
        scopes.put("PerformanceRecord", scope(new Rule("CUSTOM", json("[\"viewerId\",\"=\",\"USER_EMP_ID\"]"))));
        scopes.put("PerformancePlan", scope(Rule.ALL));
        scopes.put(DIRECTORY, scope(Rule.ALL));
        Grants superiorShape = new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), scopes);

        assertThat(assign(superiorShape, boundedManager(true))).isPresent();
    }

    @Test
    void assigningARoleGrantedEveryDepartmentIsRefused() {
        // ALL on the grant reads as no bound at all, and its rules read everything.
        Map<String, Scope> scopes = new LinkedHashMap<>();
        scopes.put(EMPLOYEE, scope(Rule.ALL));
        scopes.put(LEAVE, scope(Rule.ALL));
        Grants payrollShape = new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), scopes);

        assertThat(assign(payrollShape, boundedManager(true)))
                .contains(new Violation(Violation.Kind.DEPARTMENTS, EMPLOYEE));
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

    /** A caller role reading every ticket and every department, bounded to these department paths (null = unbounded). */
    private static Grants caller(Set<String> departmentPaths) {
        Grants caller = role(departmentPaths);
        caller.scopes().put(ModelConstant.DEPARTMENT_MODEL, scope(Rule.ALL));
        return caller;
    }

    /** A self-service role: nobody bounded it by department, and it reads no department. */
    private static Grants selfService() {
        return new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashMap<>());
    }

    private static Scope scope(Rule... rules) {
        return new Scope(new LinkedHashSet<>(List.of(rules)), null);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}

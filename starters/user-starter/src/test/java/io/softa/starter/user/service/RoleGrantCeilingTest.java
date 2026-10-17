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

import static org.assertj.core.api.Assertions.assertThat;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.user.service.RoleGrantCeiling.Grants;
import io.softa.starter.user.service.RoleGrantCeiling.Rule;
import io.softa.starter.user.service.RoleGrantCeiling.Scope;

/**
 * A role manager without an admin bypass hands out only what they hold: pages, actions, field sets,
 * and — per action — rows one of their own roles reaches.
 */
class RoleGrantCeilingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> MODEL_BY_PERMISSION = Map.of(
            "employee.view", "Employee",
            "employee.update", "Employee",
            "payroll.view", "PayRun");
    private static final Predicate<String> MULTI_COMPANY = "Employee"::equals;

    private static final Rule ALL = Rule.ALL;
    private static final Rule DEPT = new Rule("DEPT_SUBTREE", null);
    private static final Rule SELF = new Rule("SELF", null);

    @Test
    void passesARoleThatRepeatsTheCallersOwnGrants() {
        Grants hr = grants(Set.of("nav.employee"), Set.of("employee.view", "employee.update"), Set.of("sfs.salary"),
                Map.of("Employee", scope(DEPT)));
        Grants target = grants(Set.of("nav.employee"), Set.of("employee.view"), Set.of("sfs.salary"),
                Map.of("Employee", scope(DEPT)));

        assertThat(check(target, hr)).isEmpty();
    }

    @Test
    void refusesAPermissionTheCallerDoesNotHold() {
        Grants hr = grants(Set.of("nav.employee"), Set.of("employee.view"), Set.of(), Map.of("Employee", scope(ALL)));
        Grants target = grants(Set.of("nav.employee"), Set.of("employee.view", "payroll.view"), Set.of(), Map.of());

        assertThat(check(target, hr)).hasValueSatisfying(r -> assertThat(r).contains("payroll.view"));
    }

    @Test
    void refusesANavigationOrFieldSetTheCallerDoesNotHold() {
        Grants hr = grants(Set.of("nav.employee"), Set.of(), Set.of("sfs.contact"), Map.of());

        assertThat(check(grants(Set.of("nav.payroll"), Set.of(), Set.of(), Map.of()), hr)).isPresent();
        assertThat(check(grants(Set.of(), Set.of(), Set.of("sfs.salary"), Map.of()), hr)).isPresent();
    }

    @Test
    void refusesWiderRowsThanTheCallerReaches() {
        Grants hr = grants(Set.of(), Set.of("employee.view"), Set.of(), Map.of("Employee", scope(DEPT)));
        Grants target = grants(Set.of(), Set.of("employee.view"), Set.of(), Map.of("Employee", scope(ALL)));

        assertThat(check(target, hr)).hasValueSatisfying(r -> assertThat(r).contains("Employee"));
    }

    @Test
    void anActionAndItsRowsMustComeFromOneCallerRole() {
        // One role views everyone, another edits only the caller's own record. Together they do not
        // amount to editing everyone, so a role that edits everyone is out of reach.
        Grants viewAll = grants(Set.of(), Set.of("employee.view"), Set.of(), Map.of("Employee", scope(ALL)));
        Grants editSelf = grants(Set.of(), Set.of("employee.update"), Set.of(), Map.of("Employee", scope(SELF)));
        Grants target = grants(Set.of(), Set.of("employee.update"), Set.of(), Map.of("Employee", scope(ALL)));

        assertThat(RoleGrantCeiling.firstViolation(target, List.of(viewAll, editSelf), MODEL_BY_PERMISSION, MULTI_COMPANY))
                .isPresent();
    }

    @Test
    void refusesDroppingTheConditionThatNarrowsTheCallersRows() {
        JsonNode onlyActive = json("[[\"status\",\"=\",\"ACTIVE\"]]");
        Grants hr = grants(Set.of(), Set.of("employee.view"), Set.of(),
                Map.of("Employee", new Scope(Set.of(ALL), onlyActive)));
        Grants withCondition = grants(Set.of(), Set.of("employee.view"), Set.of(),
                Map.of("Employee", new Scope(Set.of(ALL), onlyActive)));
        Grants without = grants(Set.of(), Set.of("employee.view"), Set.of(), Map.of("Employee", scope(ALL)));

        assertThat(check(withCondition, hr)).isEmpty();
        assertThat(check(without, hr)).isPresent();
    }

    @Test
    void refusesWiderCompaniesOnAMultiCompanyModel() {
        Rule companyA = new Rule("CUSTOM", json("[[\"id\",\"=\",1]]"));
        Grants hr = grants(Set.of(), Set.of("employee.view"), Set.of(),
                Map.of("Employee", scope(ALL), "Company", scope(companyA)));
        Grants everyCompany = grants(Set.of(), Set.of("employee.view"), Set.of(), Map.of("Employee", scope(ALL)));
        Grants sameCompany = grants(Set.of(), Set.of("employee.view"), Set.of(),
                Map.of("Employee", scope(ALL), "Company", scope(companyA)));

        assertThat(check(everyCompany, hr)).isPresent();
        assertThat(check(sameCompany, hr)).isEmpty();
    }

    @Test
    void refusesAnUnscopedRoleWhereTheCallerIsScoped() {
        // No rule falls back to the model's own default, which cannot be compared with explicit rules.
        Grants hr = grants(Set.of(), Set.of("employee.view"), Set.of(), Map.of("Employee", scope(DEPT)));
        Grants target = grants(Set.of(), Set.of("employee.view"), Set.of(), Map.of());

        assertThat(check(target, hr)).isPresent();
    }

    @Test
    void aCallerWithoutRolesReachesNothing() {
        Grants target = grants(Set.of("nav.employee"), Set.of(), Set.of(), Map.of());

        assertThat(RoleGrantCeiling.firstViolation(target, List.of(), MODEL_BY_PERMISSION, MULTI_COMPANY)).isPresent();
    }

    @Test
    void appliesOnlyToCallersWithoutAnAdminBypass() {
        assertThat(as(Set.of("HR_ADMIN"), false)).isTrue();
        assertThat(as(Set.of(), false)).isTrue();
        assertThat(as(Set.of("TENANT_ADMIN"), false)).isFalse();
        assertThat(as(Set.of("CONSULTANT"), false)).isFalse();
        assertThat(as(Set.of("SUPER_ADMIN"), false)).isFalse();
        assertThat(as(Set.of(), true)).isFalse();
    }

    @Test
    void readsRulesAsWritten() {
        Set<Rule> rules = RoleGrantCeiling.rulesOf(json(
                "[{\"scopeType\":\"ALL\",\"scopeExpr\":{\"x\":1}},{\"scopeType\":\"SELF\"},{\"noType\":true}]"));

        assertThat(rules).containsExactly(ALL, SELF);
    }

    private static boolean as(Set<String> roleCodes, boolean system) {
        Context context = new Context();
        context.setRoleCodes(roleCodes);
        context.setSkipPermissionCheck(system);
        return ContextHolder.callWith(context, RoleGrantCeiling::applies);
    }

    private static Optional<String> check(Grants target, Grants caller) {
        return RoleGrantCeiling.firstViolation(target, List.of(caller), MODEL_BY_PERMISSION, MULTI_COMPANY);
    }

    private static Grants grants(Set<String> navs, Set<String> perms, Set<String> sfs, Map<String, Scope> scopes) {
        return new Grants(new LinkedHashSet<>(navs), new LinkedHashSet<>(perms), new LinkedHashSet<>(sfs),
                new LinkedHashMap<>(scopes));
    }

    private static Scope scope(Rule... rules) {
        return new Scope(new LinkedHashSet<>(List.of(rules)), null);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}

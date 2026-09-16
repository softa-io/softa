package io.softa.starter.permission.scope.contributor;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.scope.DepartmentIdPathResolver;
import io.softa.starter.permission.scope.DepartmentSubtreeFilterRewriter;
import io.softa.starter.permission.spi.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CUSTOM scope contributor — post-refactor it is a pure deserializer:
 * {@code Filters.of(rule.getScopeExpr().toString())} with fail-closed
 * handling of bad input. It no longer performs any {@code $principal.xxx}
 * substitution (that concept is gone) and env placeholders in leaf VALUES
 * (USER_ID / USER_DEPT_ID / …) are left untouched — they are resolved later
 * by FilterUnitParser at SQL-build time, not here.
 */
class CustomScopeContributorTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private CustomScopeContributor contributor;

    @BeforeEach
    void setUp() {
        // A real rewriter, not a mock: it returns filters carrying no CHILD OF untouched, so every
        // assertion below is unaffected — and a mock returning null would hide that.
        contributor = new CustomScopeContributor(
                new DepartmentSubtreeFilterRewriter(mock(DepartmentIdPathResolver.class)));
    }

    @Test
    void scopeType_isCustom() {
        assertThat(contributor.scopeType()).isEqualTo(ScopeType.CUSTOM);
    }

    // ─── fail-closed inputs ───

    @Test
    void compile_nullExpr_returnsEmpty() {
        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, null), "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_nonArrayExpr_returnsEmpty() {
        // A scalar (non-array) scopeExpr can't be a Filters tuple array.
        Filters out = contributor.compile(
                new ScopeRule(ScopeType.CUSTOM, JSON.textNode("not-an-array")), "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_emptyArrayExpr_returnsEmpty() {
        Filters out = contributor.compile(
                new ScopeRule(ScopeType.CUSTOM, JSON.arrayNode()), "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_unparseableExpr_returnsEmpty() {
        // Leaf-shaped tuple with an unknown operator — Filters.of throws while
        // parsing; the contributor catches and fails closed.
        ArrayNode leaf = JSON.arrayNode();
        leaf.add("status").add("NOTANOP").add("Active");
        ArrayNode expr = JSON.arrayNode();
        expr.add(leaf);

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    // ─── happy path: pure deserialization, no substitution ───

    @Test
    void compile_literalFilterArray_roundTrips() {
        // [["status","=","Active"]] deserializes straight through Filters.of and
        // is returned unchanged — same shape as parsing the JSON directly.
        ArrayNode leaf = JSON.arrayNode();
        leaf.add("status").add("=").add("Active");
        ArrayNode expr = JSON.arrayNode();
        expr.add(leaf);

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), "AnyModel");

        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(out).isEqualTo(Filters.of("[[\"status\",\"=\",\"Active\"]]"));
    }

    @Test
    void compile_envParamValue_returnedAsIs() {
        // An env-placeholder VALUE (USER_DEPT_ID) is NOT substituted here — that
        // is FilterUnitParser's job at SQL-build time. This contributor just
        // round-trips the authored array; the placeholder survives as data.
        // Bound context carries the EmpInfo the placeholder resolves against, so the
        // rule survives the fail-closed guard (see the next test for the other case).
        ArrayNode leaf = JSON.arrayNode();
        leaf.add("departmentId").add("=").add("USER_DEPT_ID");
        ArrayNode expr = JSON.arrayNode();
        expr.add(leaf);

        Filters out = ContextHolder.callWith(ctxWithEmp(),
                () -> contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), "AnyModel"));

        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(out).isEqualTo(Filters.of("[[\"departmentId\",\"=\",\"USER_DEPT_ID\"]]"));
    }

    @Test
    void compile_envParamValue_noEmpInfo_failsClosed() {
        // A pure user (no employee identity) cannot resolve USER_DEPT_ID. Drop the whole rule
        // — contributing "no rows" — instead of letting it reach FilterUnitParser, which throws
        // while the SQL is built and surfaces as a 500 on the page.
        ArrayNode leaf = JSON.arrayNode();
        leaf.add("departmentId").add("=").add("USER_DEPT_ID");
        ArrayNode expr = JSON.arrayNode();
        expr.add(leaf);

        Context ctx = new Context();
        ctx.setUserId(1L);   // no EmpInfo bound

        Filters out = ContextHolder.callWith(ctx,
                () -> contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), "AnyModel"));

        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_timeParamValue_needsNoEmpInfo() {
        // TODAY resolves off the clock, not the caller — a pure user keeps such a rule.
        ArrayNode leaf = JSON.arrayNode();
        leaf.add("hireDate").add("=").add("TODAY");
        ArrayNode expr = JSON.arrayNode();
        expr.add(leaf);

        Context ctx = new Context();
        ctx.setUserId(1L);

        Filters out = ContextHolder.callWith(ctx,
                () -> contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), "AnyModel"));

        assertThat(Filters.isEmpty(out)).isFalse();
    }

    private static Context ctxWithEmp() {
        EmpInfo info = new EmpInfo();
        info.setEmpId(7L);
        info.setDeptId(3L);
        Context ctx = new Context();
        ctx.setUserId(1L);
        ctx.setEmpInfo(info);
        return ctx;
    }
}

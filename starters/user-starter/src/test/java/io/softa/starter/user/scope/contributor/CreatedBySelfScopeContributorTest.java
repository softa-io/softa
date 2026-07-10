package io.softa.starter.user.scope.contributor;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;

class CreatedBySelfScopeContributorTest {

    private CreatedBySelfScopeContributor contributor;

    @BeforeEach
    void setUp() {
        contributor = new CreatedBySelfScopeContributor();
    }

    @Test
    void scopeType_isCreatedBySelf() {
        assertThat(contributor.scopeType()).isEqualTo(ScopeType.CREATED_BY_SELF);
    }

    @Test
    void applicableFields_isCreatedId() {
        assertThat(contributor.applicableFields()).containsExactly(ModelConstant.CREATED_ID);
    }

    @Test
    void isApplicableTo_modelHasCreatedIdField_true() {
        // The default isApplicableTo returns true when any anchor is a direct
        // field on the model. Every AuditableModel descendant carries createdId.
        assertThat(contributor.isApplicableTo("AnyModel", Set.of("id", "createdId"))).isTrue();
    }

    @Test
    void isApplicableTo_modelMissingCreatedIdField_false() {
        // A rare non-AuditableModel with no createdId field — degrades to false.
        assertThat(contributor.isApplicableTo("Weird", Set.of("id"))).isFalse();
    }

    @Test
    void compile_noContextBound_returnsEmpty() {
        // No context bound to this thread → ContextHolder.getContext() yields a
        // fresh Context whose userId is null → the contributor fails closed.
        Filters out = contributor.compile(rule(), "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_nullUserIdInContext_returnsEmpty() {
        // A bound context with no userId set (null) → fail-closed empty filter.
        Context ctx = new Context();   // userId defaults to null
        Filters out = ContextHolder.callWith(ctx, () -> contributor.compile(rule(), "AnyModel"));
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_userIdPresent_producesCreatedIdEqualsFilter() {
        // userId is read from ContextHolder.getContext(), not from a Principal.
        Filters out = ContextHolder.callWith(ctx(42L),
                () -> contributor.compile(rule(), "AnyModel"));

        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(java.util.Objects.equals(out, io.softa.starter.user.scope.ScopeRuleCompiler.matchNone())).isFalse();
        // Verify the anchor field is createdId — string form contains it.
        assertThat(out.toString()).contains("createdId");
    }

    @Test
    void compile_ignoresScopeExprRuleField() {
        // CREATED_BY_SELF doesn't consult rule.scopeExpr — the anchor is
        // fixed. Even if the caller supplies an expr, output shape stays the
        // same.
        ScopeRule r = new ScopeRule(ScopeType.CREATED_BY_SELF,
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("ignored", "value"));
        Filters out = ContextHolder.callWith(ctx(7L),
                () -> contributor.compile(r, "AnyModel"));
        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(out.toString()).contains("createdId");
    }

    // ─── helpers ───

    private static ScopeRule rule() {
        return new ScopeRule(ScopeType.CREATED_BY_SELF, null);
    }

    private static Context ctx(Long userId) {
        Context c = new Context();
        c.setUserId(userId);
        return c;
    }
}

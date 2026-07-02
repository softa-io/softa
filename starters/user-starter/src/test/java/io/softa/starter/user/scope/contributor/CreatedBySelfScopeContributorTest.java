package io.softa.starter.user.scope.contributor;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.Principal;
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
    void compile_nullPrincipal_returnsEmpty() {
        Filters out = contributor.compile(rule(), null, "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_nullUserId_returnsEmpty() {
        Filters out = contributor.compile(rule(), Principal.builder().build(), "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_userIdPresent_producesCreatedIdEqualsFilter() {
        Principal p = Principal.builder().userId(42L).build();
        Filters out = contributor.compile(rule(), p, "AnyModel");

        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(Filters.isNever(out)).isFalse();
        // Verify the anchor field is createdId — string form contains it.
        String s = out.toString();
        assertThat(s).contains("createdId");
    }

    @Test
    void compile_ignoresScopeExprRuleField() {
        // CREATED_BY_SELF doesn't consult rule.scopeExpr — the anchor is
        // fixed. Even if the caller supplies an expr, output shape stays the
        // same.
        ScopeRule r = new ScopeRule(ScopeType.CREATED_BY_SELF,
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("ignored", "value"));
        Principal p = Principal.builder().userId(7L).build();
        Filters out = contributor.compile(r, p, "AnyModel");
        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(out.toString()).contains("createdId");
    }

    // ─── helper ───

    private static ScopeRule rule() {
        return new ScopeRule(ScopeType.CREATED_BY_SELF, null);
    }
}

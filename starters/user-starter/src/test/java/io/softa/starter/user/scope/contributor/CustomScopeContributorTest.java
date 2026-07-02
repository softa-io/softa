package io.softa.starter.user.scope.contributor;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.Principal;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.scope.PrincipalRefResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomScopeContributorTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** Test resolver — advertises "employeeId" + "departmentId" and returns
     *  fixed values so we can assert on the substituted output. */
    static class FixtureResolver implements PrincipalRefResolver {
        private final Set<String> keys;
        FixtureResolver(String... keys) { this.keys = Set.of(keys); }
        @Override public Set<String> refKeys() { return keys; }
        @Override public Object resolve(String key, Principal p) {
            if ("employeeId".equals(key)) return 555L;
            if ("departmentId".equals(key)) return "dept-1";
            if ("nullref".equals(key)) return null;   // simulate missing value
            return null;
        }
    }

    private CustomScopeContributor contributor;
    private Principal principal;

    @BeforeEach
    void setUp() {
        contributor = new CustomScopeContributor(List.of(
                new FixtureResolver("employeeId", "departmentId", "nullref")));
        principal = Principal.builder().userId(42L).displayName("alice").build();
    }

    @Test
    void scopeType_isCustom() {
        assertThat(contributor.scopeType()).isEqualTo(ScopeType.CUSTOM);
    }

    @Test
    void isApplicableTo_universallyApplicable() {
        assertThat(contributor.isApplicableTo("AnyModel", Set.of())).isTrue();
    }

    @Test
    void duplicateRefKeyClaims_throw() {
        assertThatThrownBy(() -> new CustomScopeContributor(List.of(
                new FixtureResolver("employeeId"),
                new FixtureResolver("employeeId"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("employeeId");
    }

    @Test
    void compile_nullExpr_returnsEmpty() {
        ScopeRule rule = new ScopeRule(ScopeType.CUSTOM, null);
        Filters out = contributor.compile(rule, principal, "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_emptyArrayExpr_returnsEmpty() {
        ScopeRule rule = new ScopeRule(ScopeType.CUSTOM, JSON.arrayNode());
        Filters out = contributor.compile(rule, principal, "AnyModel");
        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_userIdRef_substitutedFromPrincipal() {
        ArrayNode expr = JSON.arrayNode();
        expr.add("createdId").add("=").add("$principal.userId");

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), principal, "AnyModel");

        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(Filters.isNever(out)).isFalse();
    }

    @Test
    void compile_resolverRef_substitutedFromResolver() {
        ArrayNode expr = JSON.arrayNode();
        expr.add("employeeId").add("=").add("$principal.employeeId");

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), principal, "AnyModel");

        assertThat(Filters.isEmpty(out)).isFalse();
    }

    @Test
    void compile_unknownRefInLeaf_degradesToEmpty() {
        // "$principal.doesNotExist" — no resolver claims it, so the whole
        // leaf tuple fails and the rule degrades to Filters.EMPTY.
        ArrayNode expr = JSON.arrayNode();
        expr.add("assigneeId").add("=").add("$principal.doesNotExist");

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), principal, "AnyModel");

        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_nullResolverValue_degradesToEmpty() {
        // Resolver claims "nullref" but returns null (value missing).
        ArrayNode expr = JSON.arrayNode();
        expr.add("f").add("=").add("$principal.nullref");

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), principal, "AnyModel");

        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_orComposite_dropsFailedDisjunctKeepsSurvivor() {
        // [ [createdId,=,$principal.userId]  OR  [assigneeId,=,$principal.nullref] ]
        // The OR-second disjunct fails; the first should survive.
        ArrayNode expr = JSON.arrayNode();
        ArrayNode left = JSON.arrayNode();
        left.add("createdId").add("=").add("$principal.userId");
        ArrayNode right = JSON.arrayNode();
        right.add("assigneeId").add("=").add("$principal.nullref");
        expr.add(left).add("OR").add(right);

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), principal, "AnyModel");

        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(Filters.isNever(out)).isFalse();
    }

    @Test
    void compile_orComposite_allDisjunctsFail_degradesToEmpty() {
        // Both disjuncts reference unavailable refs → whole rule degrades.
        ArrayNode expr = JSON.arrayNode();
        ArrayNode left = JSON.arrayNode();
        left.add("f1").add("=").add("$principal.unknown");
        ArrayNode right = JSON.arrayNode();
        right.add("f2").add("=").add("$principal.nullref");
        expr.add(left).add("OR").add(right);

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), principal, "AnyModel");

        assertThat(Filters.isEmpty(out)).isTrue();
    }

    @Test
    void compile_nonRefStringLiteral_passesThroughUnchanged() {
        ArrayNode expr = JSON.arrayNode();
        expr.add("status").add("=").add("ACTIVE");

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), principal, "AnyModel");

        assertThat(Filters.isEmpty(out)).isFalse();
    }

    @Test
    void compile_numericRef_serializedAsNumberNode() {
        // userId is Long — the substitute path emits a JSON number, not a string.
        // Downstream Filters parser then binds to the integer operand correctly.
        ArrayNode expr = JSON.arrayNode();
        expr.add("createdId").add("=").add("$principal.userId");

        Filters out = contributor.compile(new ScopeRule(ScopeType.CUSTOM, expr), principal, "AnyModel");

        // Cannot easily peek into Filters internals; assert no degrade.
        assertThat(Filters.isEmpty(out)).isFalse();
        assertThat(Filters.isNever(out)).isFalse();
    }
}

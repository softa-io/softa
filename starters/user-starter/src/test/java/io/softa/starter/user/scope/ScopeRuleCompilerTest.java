package io.softa.starter.user.scope;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopeRuleCompilerTest {

    /** Contributor stub — returns a preset filter and records the call. */
    static class StubContributor implements ScopeContributor {
        final ScopeType type;
        final Filters output;
        int calls;
        StubContributor(ScopeType type, Filters output) {
            this.type = type;
            this.output = output;
        }
        @Override public ScopeType scopeType() { return type; }
        @Override public List<String> applicableFields() { return List.of(); }
        @Override public boolean isApplicableTo(String model, Set<String> fields) { return true; }
        @Override public Filters compile(ScopeRule r, String m) {
            calls++;
            return output;
        }
    }

    private static ScopeApplicabilityResolver applicabilityAllowingAll() {
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        when(applicability.applicableFor(anyString())).thenReturn(EnumSet.allOf(ScopeType.class));
        return applicability;
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    @Test
    void duplicateContributorForSameType_throws() {
        assertThatThrownBy(() -> new ScopeRuleCompiler(
                applicabilityAllowingAll(),
                List.of(
                        new StubContributor(ScopeType.SELF, Filters.of("id", io.softa.framework.base.enums.Operator.EQUAL, 1L)),
                        new StubContributor(ScopeType.SELF, Filters.of("id", io.softa.framework.base.enums.Operator.EQUAL, 2L)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SELF");
    }

    @Test
    void compile_nullRules_returnsNever() {
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(), List.of());
        Filters out = compiler.compile(null, "Employee");
        assertThat(java.util.Objects.equals(out, ScopeRuleCompiler.matchNone())).isTrue();
    }

    @Test
    void compile_emptyRules_returnsNever() {
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(), List.of());
        Filters out = compiler.compile(List.of(), "Employee");
        assertThat(java.util.Objects.equals(out, ScopeRuleCompiler.matchNone())).isTrue();
    }

    @Test
    void matchNone_rendersAsOneEqualsZero() {
        Filters mn = ScopeRuleCompiler.matchNone();
        io.softa.framework.orm.domain.FilterUnit unit = mn.getFilterUnit();
        // matchNone() is an empty-tuple IN leaf: [id, id] IN ()
        assertThat(unit.isTuple()).isTrue();
        assertThat(unit.getOperator()).isEqualTo(io.softa.framework.base.enums.Operator.IN);
        assertThat((java.util.Collection<?>) unit.getValue()).isEmpty();
        // ...which the SQL builder (FilterUnitParser.parseTuple) renders as the
        // fail-closed literal "1 = 0". The empty-value branch returns before any
        // SqlWrapper use, so a null wrapper is fine here.
        java.util.List<String> aliases = unit.getFields().stream().map(f -> "t." + f).toList();
        StringBuilder sql = io.softa.framework.orm.jdbc.database.parser.FilterUnitParser
                .parseTuple(null, aliases, unit);
        assertThat(sql.toString()).isEqualTo("1 = 0");
    }

    @Test
    void compile_anyAllRule_returnsNull_meaningNoRestriction() {
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(), List.of());
        List<ScopeRule> rules = List.of(rule(ScopeType.ALL));
        Filters out = compiler.compile(rules, "Employee");
        assertThat(out).isNull();
    }

    @Test
    void compile_anyAllAmongOthers_shortCircuitsToNull() {
        StubContributor self = new StubContributor(ScopeType.SELF,
                Filters.of("id", io.softa.framework.base.enums.Operator.EQUAL, 1L));
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(), List.of(self));
        // ALL wins even if a narrower rule is present alongside it.
        List<ScopeRule> rules = List.of(rule(ScopeType.SELF), rule(ScopeType.ALL));
        Filters out = compiler.compile(rules, "Employee");
        assertThat(out).isNull();
        assertThat(self.calls).isZero();
    }

    @Test
    void compile_missingContributor_degradesToFailClosed() {
        // No contributor registered for SELF → rule degrades → all-degraded
        // means the compile output is Filters.never().
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(), List.of());
        Filters out = compiler.compile(List.of(rule(ScopeType.SELF)), "Employee");
        assertThat(java.util.Objects.equals(out, ScopeRuleCompiler.matchNone())).isTrue();
    }

    @Test
    void compile_singleContributor_returnsItsOutput() {
        Filters produced = Filters.of("employeeId", io.softa.framework.base.enums.Operator.EQUAL, 99L);
        StubContributor self = new StubContributor(ScopeType.SELF, produced);
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(), List.of(self));

        Filters out = compiler.compile(List.of(rule(ScopeType.SELF)), "Employee");

        assertThat(out).isNotNull();
        assertThat(java.util.Objects.equals(out, ScopeRuleCompiler.matchNone())).isFalse();
        assertThat(self.calls).isEqualTo(1);
    }

    @Test
    void compile_multipleNonAllRules_orMerged() {
        StubContributor self = new StubContributor(ScopeType.SELF,
                Filters.of("id", io.softa.framework.base.enums.Operator.EQUAL, 1L));
        StubContributor created = new StubContributor(ScopeType.CREATED_BY_SELF,
                Filters.of("createdId", io.softa.framework.base.enums.Operator.EQUAL, 42L));
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(),
                List.of(self, created));

        Filters out = compiler.compile(
                List.of(rule(ScopeType.SELF), rule(ScopeType.CREATED_BY_SELF)),
                "Employee");

        assertThat(out).isNotNull();
        assertThat(java.util.Objects.equals(out, ScopeRuleCompiler.matchNone())).isFalse();
        assertThat(self.calls).isEqualTo(1);
        assertThat(created.calls).isEqualTo(1);
    }

    @Test
    void compile_contributorThrows_degradesToEmptyForThatRule() {
        ScopeContributor throwing = new ScopeContributor() {
            @Override public ScopeType scopeType() { return ScopeType.SELF; }
            @Override public List<String> applicableFields() { return List.of(); }
            @Override public boolean isApplicableTo(String m, Set<String> f) { return true; }
            @Override public Filters compile(ScopeRule r, String m) {
                throw new RuntimeException("boom");
            }
        };
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(), List.of(throwing));

        // The one rule degrades → compile output collapses to Filters.never().
        Filters out = compiler.compile(List.of(rule(ScopeType.SELF)), "Employee");
        assertThat(java.util.Objects.equals(out, ScopeRuleCompiler.matchNone())).isTrue();
    }

    @Test
    void compile_illegalStateFromContributor_propagates() {
        ScopeContributor throwing = new ScopeContributor() {
            @Override public ScopeType scopeType() { return ScopeType.DEPT_SUBTREE; }
            @Override public List<String> applicableFields() { return List.of(); }
            @Override public boolean isApplicableTo(String m, Set<String> f) { return true; }
            @Override public Filters compile(ScopeRule r, String m) {
                throw new IllegalStateException("config invariant broken");
            }
        };
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicabilityAllowingAll(), List.of(throwing));

        assertThatThrownBy(() -> compiler.compile(
                List.of(rule(ScopeType.DEPT_SUBTREE)), "Employee"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compile_inapplicableToModel_degradesToEmpty() {
        ScopeApplicabilityResolver applicability = mock(ScopeApplicabilityResolver.class);
        // Only ALL is applicable — SELF is not → the SELF rule degrades.
        when(applicability.applicableFor(anyString())).thenReturn(EnumSet.of(ScopeType.ALL));

        StubContributor self = new StubContributor(ScopeType.SELF,
                Filters.of("id", io.softa.framework.base.enums.Operator.EQUAL, 1L));
        ScopeRuleCompiler compiler = new ScopeRuleCompiler(applicability, List.of(self));

        Filters out = compiler.compile(List.of(rule(ScopeType.SELF)), "Employee");

        assertThat(java.util.Objects.equals(out, ScopeRuleCompiler.matchNone())).isTrue();
        assertThat(self.calls).isZero();
    }

    // ─── helpers ───

    private static ScopeRule rule(ScopeType type) {
        return new ScopeRule(type, JsonNodeFactory.instance.nullNode());
    }
}

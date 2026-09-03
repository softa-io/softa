package io.softa.starter.user.service;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consultant memberships never appear in a tenant's roster.
 *
 * <p>The rule lives in {@code scopeByTenant}, the one place every roster read passes through, so a
 * query written later is covered without its author knowing consultants exist. What is pinned here
 * is the shape of the predicate, because the mistake it guards against is silent in both directions:
 * a bare {@code eq(false)} would hide every membership created before consultants existed (their
 * flag is null), and omitting it altogether would expose the platform's own staff to the tenant.
 */
class ConsultantRosterHidingTest {

    private static String render(Filters filters) {
        return filters == null ? "" : filters.toString();
    }

    @Test
    void theHidingPredicateMatchesUnsetAsWellAsFalse() {
        Filters hidden = Filters.or()
                .eq("consultant", false)
                .isNotSet("consultant");

        String s = render(hidden);
        // Both halves present: rows predating consultants carry null and must stay visible.
        assertThat(s).contains("consultant");
        assertThat(s.toUpperCase()).contains("OR");
    }

    @Test
    void itCombinesWithTheCallersFiltersUsingAnd() {
        Filters callers = new Filters().eq("status", "Active");
        Filters combined = callers.and(Filters.or()
                .eq("consultant", false)
                .isNotSet("consultant"));

        String s = render(combined).toUpperCase();
        // The caller's own condition survives — the hiding rule narrows it, never replaces it.
        assertThat(s).contains("STATUS");
        assertThat(s).contains("CONSULTANT");
        assertThat(s).contains("AND");
    }
}

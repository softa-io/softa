package io.softa.framework.base.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@code copy()} is how every isolated read is made — a scope waiver, a cleared selection, a per-tenant
 * scope — and a field it forgets is silently absent on that copy. For the company / country sets that
 * means the narrowing reads "unknown" and does nothing, on exactly the reads that were meant to stay
 * narrowed. For the permission waiver it meant the opposite: a job running as the system cloned a context
 * for one tenant and read it under the row scope of whoever started it, finding almost nothing.
 */
class ContextCopyTest {

    @Test
    void copyCarriesTheGrantedCompaniesAndCountries() {
        Context original = new Context();
        original.setUserId(1L);
        original.setGrantedCompanyIds(Set.of(11L, 12L));
        original.setGrantedCountries(Set.of("SG", "NZ"));

        Context copy = original.copy();

        assertThat(copy.getGrantedCompanyIds()).containsExactlyInAnyOrder(11L, 12L);
        assertThat(copy.getGrantedCountries()).containsExactlyInAnyOrder("SG", "NZ");
    }

    @Test
    void copyCarriesTheWaivedPermissionCheck() {
        Context system = new Context();
        system.setSkipPermissionCheck(true);
        assertThat(system.copy().isSkipPermissionCheck()).isTrue();

        assertThat(new Context().copy().isSkipPermissionCheck()).isFalse();
    }

    @Test
    void copyKeepsUnknownAsNull() {
        Context copy = new Context().copy();
        assertThat(copy.getGrantedCompanyIds()).isNull();
        assertThat(copy.getGrantedCountries()).isNull();
    }
}

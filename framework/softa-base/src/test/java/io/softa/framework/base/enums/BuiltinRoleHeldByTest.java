package io.softa.framework.base.enums;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Does this principal hold this role?" — asked in one place.
 *
 * <p>Fourteen call sites across the starters asked it by hand, and not all of them guarded the null:
 * a snapshot that failed to build leaves the code set null, and an unguarded {@code contains} turns
 * that into an NPE inside the permission gate rather than the refusal it should be. Null holding
 * nothing is the load-bearing case here, not a formality.
 */
class BuiltinRoleHeldByTest {

    @Test
    void aHeldCodeIsHeld() {
        assertThat(BuiltinRole.SUPER_ADMIN.heldBy(Set.of("SUPER_ADMIN"))).isTrue();
    }

    @Test
    void anUnheldCodeIsNot() {
        // The paired case: a predicate that answered true for everything would satisfy the first.
        assertThat(BuiltinRole.SUPER_ADMIN.heldBy(Set.of("TENANT_ADMIN"))).isFalse();
    }

    @Test
    void nullHoldsNothingRatherThanThrowing() {
        assertThat(BuiltinRole.SUPER_ADMIN.heldBy(null)).isFalse();
        assertThat(BuiltinRole.anyHeldBy(null, BuiltinRole.SUPER_ADMIN, BuiltinRole.CONSULTANT)).isFalse();
    }

    @Test
    void anyHeldByIsAnOrOverTheRolesNamed() {
        Set<String> consultant = Set.of("CONSULTANT");
        assertThat(BuiltinRole.anyHeldBy(consultant, BuiltinRole.TENANT_ADMIN, BuiltinRole.CONSULTANT)).isTrue();
        assertThat(BuiltinRole.anyHeldBy(consultant, BuiltinRole.SUPER_ADMIN, BuiltinRole.TENANT_ADMIN)).isFalse();
        // Naming none is not "any" — an empty varargs must not answer true for everyone.
        assertThat(BuiltinRole.anyHeldBy(consultant)).isFalse();
    }
}

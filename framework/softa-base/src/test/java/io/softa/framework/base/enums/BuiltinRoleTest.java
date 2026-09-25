package io.softa.framework.base.enums;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire values of the platform's own role codes.
 *
 * <p>These strings are stored in {@code role.code}, seeded into every tenant, and compared against a
 * user's {@code roleCodes} on every request. The enum derives each one from the constant's own name,
 * which makes renaming a constant a data migration — and a silent one: a role code that matches
 * nothing grants nothing rather than failing, so it surfaces as "this administrator suddenly has no
 * permissions" rather than as an error.
 *
 * <p>Asserted as a SET of literals rather than per constant on purpose. An IDE rename of
 * {@code BuiltinRole.SUPER_ADMIN} rewrites every symbol that refers to it — including a test written
 * as {@code assertThat(SUPER_ADMIN.getCode()).isEqualTo(...)}, which would then pass again. These
 * literals share no symbol with the enum, so the rename cannot carry them along and this goes red.
 */
class BuiltinRoleTest {

    @Test
    void theCodesAreTheValuesAlreadyStoredInRoleRows() {
        assertThat(Arrays.stream(BuiltinRole.values()).map(BuiltinRole::getCode))
                .containsExactlyInAnyOrder("SUPER_ADMIN", "TENANT_ADMIN", "CONSULTANT");
    }

    @Test
    void everyRoleStillCarriesADisplayName() {
        // The paired case: getCode() now comes from name(), and a getter that started answering the
        // constant name for BOTH would satisfy the assertion above.
        assertThat(Arrays.stream(BuiltinRole.values()).map(BuiltinRole::getName))
                .containsExactlyInAnyOrder("Super Admin", "Tenant Admin", "Consultant");
    }
}

package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.UserIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * The two identifier rules in the identity service.
 *
 * <p>{@code isIdentifierClaimable} keeps a SHARED work contact from becoming someone's login
 * identifier. A work contact and a login identifier are two roles for the same string and only the
 * second has to be unique: shared work numbers are ordinary — a shop's phone, a shared floor
 * handset, a manager's number entered for a worker who has none — and copying such a number across
 * does not create a login route, it destroys one, because two identities holding one identifier
 * both resolve to "shared by more than one account".
 *
 * <p>{@code findByLoginIdentifier} refuses rather than guessing when that has happened anyway.
 */
class IdentityAdoptionTest {

    private static final Long MINE = 7L;

    private final CacheService cacheService = mock(CacheService.class);
    private final UserIdentityServiceImpl identityService =
            spy(new UserIdentityServiceImpl(cacheService));

    private static UserIdentity heldBy(Long profileId, String loginEmail, String loginMobile) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(profileId);
        identity.setLoginEmail(loginEmail);
        identity.setLoginMobile(loginMobile);
        return identity;
    }

    @Test
    void anUnclaimedIdentifierIsClaimable() {
        doReturn(List.of()).when(identityService).searchList(any(Filters.class));

        assertThat(identityService.isIdentifierClaimable("+8613800138000", MINE)).isTrue();
        assertThat(identityService.isIdentifierClaimable("alice@acme.com", MINE)).isTrue();
    }

    @Test
    void anIdentifierAnotherPersonHolds_isNotClaimable() {
        // The shared-work-number case: one number on many accounts is normal as a CONTACT, and
        // seeding it as a login identifier would take out the person who had it to themselves too.
        doReturn(List.of(heldBy(8L, null, "+8613800138000")))
                .when(identityService).searchList(any(Filters.class));

        assertThat(identityService.isIdentifierClaimable("+8613800138000", MINE)).isFalse();
    }

    @Test
    void myOwnIdentifier_isStillClaimableByMe() {
        // Only OTHER people block a claim. A row naming this same person is a re-seed, not a
        // conflict, and treating it as one would refuse a legitimate write.
        doReturn(List.of(heldBy(MINE, null, "+8613800138000")))
                .when(identityService).searchList(any(Filters.class));

        assertThat(identityService.isIdentifierClaimable("+8613800138000", MINE)).isTrue();
    }

    @Test
    void anIdentifierHeldUnderItsPreFoldSpelling_isNotClaimable() {
        // The row was seeded as "+65 9123-4567" before the fold and nothing rewrote it. Asking the
        // column only for "+6591234567" saw no claimant, and /join seeded the same number onto a
        // second identity. The store answers by exact equality, as the database does.
        UserIdentity legacy = heldBy(8L, null, "+65 9123-4567");
        doAnswer(inv -> inv.getArgument(0).toString().contains("\"+65 9123-4567\"")
                ? List.of(legacy) : List.of())
                .when(identityService).searchList(any(Filters.class));

        assertThat(identityService.isIdentifierClaimable("+65 9123-4567", MINE)).isFalse();
        assertThat(identityService.isIdentifierClaimable("+65 9123-4567", 8L)).isTrue();
    }

    @Test
    void aBlankIdentifierIsNeverClaimable() {
        assertThat(identityService.isIdentifierClaimable("  ", MINE)).isFalse();
        assertThat(identityService.isIdentifierClaimable(null, MINE)).isFalse();
    }

    @Test
    void aSharedIdentifier_isRefused_notGuessed() {
        // searchOne throws IllegalArgumentException on more than one row, quoting the filter —
        // which would surface at an anonymous endpoint as a server error naming someone's address.
        doThrow(new IllegalArgumentException("more than 1"))
                .when(identityService).searchOne(any(Filters.class));

        assertThatThrownBy(() -> identityService.findByLoginIdentifier("+6591234567"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("shared by more than one account");
    }

    @Test
    void aBlankIdentifier_resolvesToNobody() {
        assertThat(identityService.findByLoginIdentifier("  ")).isEqualTo(Optional.empty());
    }
}

package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "May a new account be created with these contacts?" has exactly one answer.
 *
 * <p>{@code newAccountRefusal} is what an import asks before it creates anything, and
 * {@code registerInvitedUser} is what then creates. If the two applied their own copies of the
 * rules, a pre-check could pass a row the create then refuses — or worse, wave through one the
 * create would have caught. So the create path throws precisely the reason the pre-check returns,
 * and these cases pin the rules down in their order of precedence.
 */
class NewAccountRefusalTest {

    private static final Long PERSON = 7L;
    private static final Long SOMEBODY_ELSE = 99L;
    private static final Long THIS_TENANT = 2L;
    private static final String EMAIL = "ada@acme.com";
    private static final String MOBILE = "+6591234567";

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());

    NewAccountRefusalTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        ReflectionTestUtils.setField(accountService, "profileService", profileService);
        doReturn(1L).when(accountService).createOne(any(UserAccount.class));
        doReturn(List.of()).when(accountService).searchList(any(Filters.class));
        doReturn(Optional.empty()).when(accountService).findMembershipInTenant(any(), any());
        doReturn(Optional.empty()).when(accountService).reviveMembership(any(), any(), any());
        when(identityService.findByLoginIdentifier(any())).thenReturn(Optional.empty());
        when(profileService.registerUserProfile(anyLong(), any(UserProfileDTO.class)))
                .thenReturn(new UserInfo());
        when(profileService.linkAccountToPerson(anyLong(), anyLong())).thenReturn(new UserInfo());
        when(profileService.getUserInfo(anyLong())).thenReturn(new UserInfo());
    }

    private String refusalInThisTenant(String email, String mobile) {
        AtomicReference<String> reason = new AtomicReference<>();
        inThisTenant(() -> reason.set(accountService.newAccountRefusal(email, mobile)));
        return reason.get();
    }

    private void inThisTenant(Runnable body) {
        Context ctx = new Context();
        ctx.setTenantId(THIS_TENANT);
        ContextHolder.runWith(ctx, body);
    }

    private void identifierBelongsTo(String identifier, Long profileId) {
        UserIdentity identity = new UserIdentity();
        identity.setProfileId(profileId);
        when(identityService.findByLoginIdentifier(identifier)).thenReturn(Optional.of(identity));
    }

    private UserAccount membershipHere(AccountStatus status) {
        UserAccount row = new UserAccount();
        row.setId(999L);
        row.setTenantId(THIS_TENANT);
        row.setProfileId(PERSON);
        row.setStatus(status);
        row.setEmail(EMAIL);
        doReturn(Optional.of(row)).when(accountService).findMembershipInTenant(THIS_TENANT, PERSON);
        return row;
    }

    /** The row the platform mints when a consultant is authorized for this company. */
    private void consultantMembershipHere() {
        UserAccount row = membershipHere(AccountStatus.ACTIVE);
        row.setConsultant(Boolean.TRUE);
    }

    private static UserAccount accountHolding(Long id) {
        UserAccount holder = new UserAccount();
        holder.setId(id);
        holder.setTenantId(THIS_TENANT);
        return holder;
    }

    // ─── the four refusals, in precedence order ───

    @Test
    void twoIdentifiersNamingTwoDifferentPeople() {
        identifierBelongsTo(EMAIL, PERSON);
        identifierBelongsTo(MOBILE, SOMEBODY_ELSE);

        assertThat(refusalInThisTenant(EMAIL, MOBILE))
                .isEqualTo("This email and mobile belong to two different people. Enter contacts for one person.");
    }

    @Test
    void theEmailIsHeldByAnotherAccountHere() {
        doReturn(List.of(accountHolding(555L))).when(accountService).searchList(any(Filters.class));

        assertThat(refusalInThisTenant(EMAIL, null)).isEqualTo("Email already exists: " + EMAIL);
    }

    @Test
    void theMobileIsHeldByAnotherAccountHere() {
        doReturn(List.of(accountHolding(555L))).when(accountService).searchList(any(Filters.class));

        assertThat(refusalInThisTenant(null, MOBILE)).isEqualTo("Mobile already exists: " + MOBILE);
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"ACTIVE", "INVITED", "PENDING", "FROZEN"})
    void thePersonAlreadyHasALiveMembershipHere(AccountStatus live) {
        identifierBelongsTo(EMAIL, PERSON);
        membershipHere(live);

        assertThat(refusalInThisTenant(EMAIL, null))
                .isEqualTo("This person is already a member of this company.");
    }

    @Test
    void aConsultantsMembershipSaysSoRatherThanNamingAMemberNobodyCanFind() {
        // The same refusal, told apart. A consultant's row is minted by the platform and hidden
        // from every roster read, so "already a member of this company" pointed the operator at an
        // account list that does not contain it and never will — a dead end with no next step.
        identifierBelongsTo(EMAIL, PERSON);
        consultantMembershipHere();

        assertThat(refusalInThisTenant(EMAIL, null))
                .isEqualTo("This person is a consultant for this company, so they cannot also be "
                        + "created as an employee here. Ask the platform administrator.");
    }

    @Test
    void anOrdinaryMembershipKeepsTheGenericWording() {
        // The paired negative: an employee's own row is visible in the account list, so naming it
        // generically is correct there and the consultant branch must not swallow it.
        identifierBelongsTo(EMAIL, PERSON);
        UserAccount row = membershipHere(AccountStatus.ACTIVE);
        row.setConsultant(Boolean.FALSE);

        assertThat(refusalInThisTenant(EMAIL, null))
                .isEqualTo("This person is already a member of this company.");
    }

    @Test
    void aContactStillOnAFormerEmployeesClosedRowIsRefusedWithTheReHireRemedy() {
        // Off-boarding released the work email from the identity, so no identity resolves and the
        // only thing carrying the address is a DEACTIVATED row here. The pre-check does not read
        // that row as "this is the person" — it says who held the address LAST, which may not be
        // who the import row describes — so it refuses, and names the explicit action instead.
        UserAccount closed = membershipHere(AccountStatus.DEACTIVATED);
        doReturn(List.of(closed)).when(accountService).searchList(any(Filters.class));

        assertThat(refusalInThisTenant(EMAIL, null))
                .isEqualTo("A former employee's closed account still holds this contact. "
                        + "Re-hire that account instead of creating a new one.");
    }

    @Test
    void aClosedRowHoldingTheMobileIsRefusedTheSameWay_evenWhenTheEmailNamesSomeoneElse() {
        // The email is a live identifier of one person; the mobile sits on somebody else's closed
        // row. That row is not read as a second person (it names an address, not a human), so this
        // is not "two people" — it is a contact HR must free up or re-hire, and it is told which.
        identifierBelongsTo(EMAIL, PERSON);
        UserAccount closed = new UserAccount();
        closed.setId(998L);
        closed.setTenantId(THIS_TENANT);
        closed.setProfileId(SOMEBODY_ELSE);
        closed.setStatus(AccountStatus.DEACTIVATED);
        closed.setMobile(MOBILE);
        doReturn(List.of(closed)).when(accountService).searchList(any(Filters.class));

        assertThat(refusalInThisTenant(EMAIL, MOBILE))
                .isEqualTo("A former employee's closed account still holds this contact. "
                        + "Re-hire that account instead of creating a new one.");
    }

    // ─── allowed ───

    @Test
    void freshContactsAreAllowed() {
        assertThat(refusalInThisTenant(EMAIL, MOBILE)).isNull();
    }

    @Test
    void aPersonEmployedElsewhereIsAllowed() {
        // Their second company: no membership here, nothing here holds the address.
        identifierBelongsTo(EMAIL, PERSON);
        identifierBelongsTo(MOBILE, PERSON);

        assertThat(refusalInThisTenant(EMAIL, MOBILE)).isNull();
    }

    @Test
    void aLeaverOfThisCompanyIsAllowed() {
        // Their own closed row still carries the address; it is what gets revived, not a duplicate.
        identifierBelongsTo(EMAIL, PERSON);
        UserAccount closed = membershipHere(AccountStatus.DEACTIVATED);
        doReturn(List.of(closed)).when(accountService).searchList(any(Filters.class));

        assertThat(refusalInThisTenant(EMAIL, null)).isNull();
    }

    // ─── one source ───

    @Test
    void theCreatePathThrowsExactlyThePreCheckReason() {
        identifierBelongsTo(EMAIL, PERSON);
        membershipHere(AccountStatus.ACTIVE);
        String reason = refusalInThisTenant(EMAIL, null);

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(EMAIL, null, "Ada"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(reason));
    }

    @Test
    void eachIdentifierIsResolvedOnce() {
        // The person lookup is the expensive, cross-tenant read of the pair; asking it twice for
        // the same identifier is what a second copy of the rules would do.
        identifierBelongsTo(EMAIL, PERSON);
        identifierBelongsTo(MOBILE, PERSON);

        inThisTenant(() -> accountService.registerInvitedUser(EMAIL, MOBILE, "Ada"));

        verify(identityService, times(1)).findByLoginIdentifier(EMAIL);
        verify(identityService, times(1)).findByLoginIdentifier(MOBILE);
    }
}

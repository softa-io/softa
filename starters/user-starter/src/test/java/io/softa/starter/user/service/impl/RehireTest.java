package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-hiring a leaver through the ordinary create path.
 *
 * <p>Every creation path funnels through {@code registerInvitedUser}, and it refused whenever the
 * person had ANY membership here — a DEACTIVATED one included. So a former employee could never be
 * added back: Add Employee said "already a member" and /join pointed at a re-hire flow that did
 * not exist. {@code uk_user_account_tenant_profile} forbids a second row per (tenant, person), so
 * the only correct shape is to revive the closed row, and it is the create path that has to do it.
 */
class RehireTest {

    private static final Long PERSON = 7L;
    private static final Long THIS_TENANT = 2L;
    private static final Long CLOSED_ROW = 999L;
    private static final String OLD_EMAIL = "ada@acme.com";
    private static final String NEW_EMAIL = "ada.l@acme.com";
    private static final String MOBILE = "+6591234567";

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final UserRoleRelService roleRelService = mock(UserRoleRelService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final io.softa.starter.user.service.UserSecurityLogService securityLog =
            AccountServiceTestSupport.quiet(accountService);

    RehireTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        ReflectionTestUtils.setField(accountService, "profileService", profileService);
        ReflectionTestUtils.setField(accountService, "roleRelService", roleRelService);
        doReturn(1L).when(accountService).createOne(any(UserAccount.class));
        doReturn(true).when(accountService).updateOne(any(UserAccount.class), anyBoolean());
        doReturn(List.of()).when(accountService).searchList(any(Filters.class));
        doReturn(Optional.empty()).when(accountService).findMembershipInTenant(any(), any());
        when(profileService.registerUserProfile(anyLong(), any(UserProfileDTO.class)))
                .thenReturn(new UserInfo());
        when(profileService.linkAccountToPerson(anyLong(), anyLong())).thenReturn(new UserInfo());
        when(profileService.getUserInfo(anyLong())).thenReturn(new UserInfo());
        // The person is known by every identifier used here.
        UserIdentity identity = new UserIdentity();
        identity.setProfileId(PERSON);
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.of(identity));
    }

    private void inThisTenant(Runnable body) {
        Context ctx = new Context();
        ctx.setTenantId(THIS_TENANT);
        ContextHolder.runWith(ctx, body);
    }

    private static UserAccount membership(AccountStatus status, String email) {
        UserAccount account = new UserAccount();
        account.setId(CLOSED_ROW);
        account.setTenantId(THIS_TENANT);
        account.setProfileId(PERSON);
        account.setStatus(status);
        account.setEmail(email);
        return account;
    }

    private void personHasMembershipHere(AccountStatus status) {
        UserAccount row = membership(status, OLD_EMAIL);
        doReturn(Optional.of(row)).when(accountService).findMembershipInTenant(THIS_TENANT, PERSON);
        doReturn(Optional.of(row)).when(accountService).reviveMembership(PERSON, NEW_EMAIL, MOBILE);
    }

    @Test
    void aLeaverReHiredThroughCreateRevivesTheClosedMembershipInsteadOfRefusing() {
        personHasMembershipHere(AccountStatus.DEACTIVATED);

        inThisTenant(() -> assertThat(accountService.registerInvitedUser(NEW_EMAIL, MOBILE, "Ada L").getUserId())
                .isEqualTo(CLOSED_ROW));

        // The row is reused with the NEW contacts — no second row, no second person. Nobody is
        // bound to it until /join, so the answer is built from the row, not read from a person.
        verify(accountService).reviveMembership(PERSON, NEW_EMAIL, MOBILE);
        verify(profileService, never()).getUserInfo(anyLong());
        verify(accountService, never()).createOne(any(UserAccount.class));
        verify(profileService, never()).registerUserProfile(anyLong(), any(UserProfileDTO.class));
        verify(profileService, never()).linkAccountToPerson(anyLong(), anyLong());
    }

    @Test
    void aStillActiveMemberHereIsStillRefused() {
        personHasMembershipHere(AccountStatus.ACTIVE);

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(NEW_EMAIL, MOBILE, "Ada L"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already a member of this company"));

        verify(accountService, never()).reviveMembership(any(), any(), any());
        verify(accountService, never()).createOne(any(UserAccount.class));
    }

    @Test
    void anInvitedMemberHereIsStillRefused() {
        // Only a CLOSED membership is revivable; an invitation in flight is a live membership.
        personHasMembershipHere(AccountStatus.INVITED);

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(NEW_EMAIL, MOBILE, "Ada L"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already a member of this company"));

        verify(accountService, never()).reviveMembership(any(), any(), any());
    }

    @Test
    void theClosedRowsOwnContactDoesNotCountAsASameTenantDuplicate() {
        // The leaver's old work address still sits on their DEACTIVATED row. Re-hiring them under
        // the same address must not trip "Email already exists" on their own closed membership.
        UserAccount closed = membership(AccountStatus.DEACTIVATED, OLD_EMAIL);
        doReturn(Optional.of(closed)).when(accountService).findMembershipInTenant(THIS_TENANT, PERSON);
        doReturn(List.of(closed)).when(accountService).searchList(any(Filters.class));
        doReturn(Optional.of(closed)).when(accountService).reviveMembership(PERSON, OLD_EMAIL, null);

        inThisTenant(() -> assertThatCode(() -> accountService.registerInvitedUser(OLD_EMAIL, null, "Ada L"))
                .doesNotThrowAnyException());

        verify(accountService).reviveMembership(PERSON, OLD_EMAIL, null);
    }

    @Test
    void aLeaverStillKnownByAPersonalLoginEmailIsRevivedThroughCreate() {
        // The leaver kept a personal login identifier (set on /join), so off-boarding released only
        // the work email and the identity still names them. Being named by a LIVE identifier — not
        // by a contact sitting on a closed row — is what makes reviving safe: the identity is the
        // person, whereas a work address only says who held it last.
        String personalEmail = "ada@personal.com";
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        UserIdentity ada = new UserIdentity();
        ada.setProfileId(PERSON);
        when(identityService.findByLoginIdentifier(personalEmail)).thenReturn(Optional.of(ada));
        UserAccount closed = membership(AccountStatus.DEACTIVATED, OLD_EMAIL);
        doReturn(Optional.of(closed)).when(accountService).findMembershipInTenant(THIS_TENANT, PERSON);
        doReturn(Optional.of(closed)).when(accountService).reviveMembership(PERSON, personalEmail, null);

        inThisTenant(() -> assertThatCode(() -> accountService.registerInvitedUser(personalEmail, null, "Ada L"))
                .doesNotThrowAnyException());

        verify(accountService).reviveMembership(PERSON, personalEmail, null);
        verify(accountService, never()).createOne(any(UserAccount.class));
    }

    // ─── a contact on a closed row names the ADDRESS's last holder, not the person being created ───

    private static UserAccount closedRowInTenant(Long tenantId, Long profileId, String email, String mobile) {
        UserAccount row = new UserAccount();
        row.setId(CLOSED_ROW);
        row.setTenantId(tenantId);
        row.setProfileId(profileId);
        row.setStatus(AccountStatus.DEACTIVATED);
        row.setEmail(email);
        row.setMobile(mobile);
        return row;
    }

    /** Only a query that names THIS tenant gets an answer; anything wider sees the other tenant's row. */
    private static boolean namesThisTenant(Filters filters) {
        return filters != null && filters.toString().contains("[\"tenantId\",\"=\"," + THIS_TENANT + "]");
    }

    @Test
    void aClosedRowHoldingTheEmailRefusesTheCreateAndPointsAtReHire() {
        // Ada left; her work email sits on her DEACTIVATED row and her identity no longer carries
        // it. HR types that address for a NEW hire. Reviving Ada's row here would make Bob's account
        // Ada's membership (her profileId) and return Ada's UserInfo to whoever typed Bob's details.
        // The create must refuse and name the remedy; it must not decide who the person is.
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        UserAccount closed = closedRowInTenant(THIS_TENANT, PERSON, OLD_EMAIL, null);
        doReturn(List.of(closed)).when(accountService).searchList(any(Filters.class));

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(OLD_EMAIL, MOBILE, "Bob"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A former employee's closed account still holds this contact. "
                        + "Re-hire that account instead of creating a new one."));

        verify(accountService, never()).reviveMembership(any(), any(), any());
        verify(accountService, never()).createOne(any(UserAccount.class));
        verify(profileService, never()).getUserInfo(anyLong());
    }

    @Test
    void aClosedRowHoldingTheMobileRefusesTheCreateTheSameWay() {
        // The pool-phone case: a company mobile on a leaver's closed row, entered for a newcomer.
        // Nothing indexes (tenant, mobile), so "the" closed row holding it is not even well-defined.
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        UserAccount closed = closedRowInTenant(THIS_TENANT, PERSON, OLD_EMAIL, MOBILE);
        doReturn(List.of(closed)).when(accountService).searchList(any(Filters.class));

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser("bob@acme.com", MOBILE, "Bob"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("A former employee's closed account still holds this contact. "
                        + "Re-hire that account instead of creating a new one."));

        verify(accountService, never()).reviveMembership(any(), any(), any());
        verify(accountService, never()).createOne(any(UserAccount.class));
    }

    @Test
    void aClosedRowInAnotherTenantIsNotThisTenantsConcern() {
        // The leaver of company 9 is a stranger to company 2: neither an anchor nor a refusal.
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        UserAccount elsewhere = closedRowInTenant(9L, PERSON, OLD_EMAIL, MOBILE);
        doReturn(List.of(elsewhere)).when(accountService)
                .searchList(argThat((Filters f) -> !namesThisTenant(f)));

        inThisTenant(() -> assertThatCode(() -> accountService.registerInvitedUser(OLD_EMAIL, MOBILE, "Ada L"))
                .doesNotThrowAnyException());

        // A fresh person is minted here, exactly as for any unknown contact.
        verify(accountService, never()).reviveMembership(any(), any(), any());
        verify(accountService).createOne(any(UserAccount.class));
        verify(profileService).registerUserProfile(anyLong(), any(UserProfileDTO.class));
    }

    @Test
    void aClosedRowWithoutAPersonIsJustAnotherHolderOfTheAddress() {
        // Nobody to re-hire, so pointing at re-hire would be a dead end: it refuses like a duplicate.
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        UserAccount orphan = closedRowInTenant(THIS_TENANT, null, OLD_EMAIL, null);
        doReturn(List.of(orphan)).when(accountService).searchList(any(Filters.class));

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(OLD_EMAIL, null, "Ada L"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Email already exists"));

        verify(accountService, never()).reviveMembership(any(), any(), any());
        verify(accountService, never()).createOne(any(UserAccount.class));
    }

    // ─── the explicit action ───

    @Test
    void reHireReopensTheRowItWasAskedToReopen_inThatRowsTenant() {
        // Re-hire is reached from a roster a platform super-admin works across tenants, so the
        // ambient tenant (2) is theirs while the row named sits in company 9. Re-querying by
        // (profileId, ambient tenant) found nothing there — or, had Ada also left company 2, her
        // closed row THERE. The loaded row is the one revived, and every uniqueness question is
        // asked of ITS company.
        UserAccount closed = closedRowInTenant(9L, PERSON, OLD_EMAIL, MOBILE);
        doReturn(Optional.of(closed)).when(accountService).getById(CLOSED_ROW);

        inThisTenant(() -> assertThatCode(() -> accountService.rehire(CLOSED_ROW)).doesNotThrowAnyException());

        // The row's OWN values. The person is detached: /join decides again who this is.
        assertThat(closed.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(closed.getProfileId()).isNull();
        assertThat(closed.getEmail()).isEqualTo(OLD_EMAIL);
        assertThat(closed.getMobile()).isEqualTo(MOBILE);
        verify(accountService).updateOne(closed, false);
        verify(accountService, never()).reviveMembership(any(), any(), any());
        // Load-bearing: the holder checks name tenant 9, the row's, and never the operator's 2.
        ArgumentCaptor<Filters> holderQueries = ArgumentCaptor.forClass(Filters.class);
        verify(accountService, times(4)).searchList(holderQueries.capture());
        assertThat(holderQueries.getAllValues()).allSatisfy(f -> assertThat(f.toString())
                .contains("[\"tenantId\",\"=\",9]")
                .doesNotContain("[\"tenantId\",\"=\"," + THIS_TENANT + "]"));
    }

    @Test
    void aMobileAnotherAccountInTheRowsTenantHolds_refusesTheReHire() {
        // The mobile is a work contact on the same footing as the email, and only the email is
        // covered by an index. Reviving a row whose number a colleague's live row now carries would
        // leave a code sent there naming two people.
        UserAccount closed = closedRowInTenant(THIS_TENANT, PERSON, OLD_EMAIL, MOBILE);
        doReturn(Optional.of(closed)).when(accountService).getById(CLOSED_ROW);
        UserAccount colleague = new UserAccount();
        colleague.setId(555L);
        colleague.setTenantId(THIS_TENANT);
        colleague.setMobile(MOBILE);
        doReturn(List.of(colleague)).when(accountService)
                .searchList(argThat((Filters f) -> f != null && f.toString().contains("\"" + MOBILE + "\"")));

        inThisTenant(() -> assertThatThrownBy(() -> accountService.rehire(CLOSED_ROW))
                .isInstanceOf(BusinessException.class)
                .hasMessage("That work mobile already belongs to another account."));

        assertThat(closed.getStatus()).isEqualTo(AccountStatus.DEACTIVATED);
        verify(accountService, never()).updateOne(any(UserAccount.class), anyBoolean());
    }

    @Test
    void revivingThroughCreate_refusesAMobileAnotherAccountHolds_notJustTheEmail() {
        // The same rule on the create-path shape: it shares the revive with rehire(), so a number
        // held elsewhere in this company is refused here too instead of only the email.
        UserAccount closed = closedRowInTenant(THIS_TENANT, PERSON, OLD_EMAIL, null);
        doReturn(List.of(closed)).when(accountService)
                .searchList(argThat((Filters f) -> f != null && f.toString().contains("\"profileId\"")));
        UserAccount colleague = new UserAccount();
        colleague.setId(555L);
        colleague.setTenantId(THIS_TENANT);
        colleague.setMobile(MOBILE);
        doReturn(List.of(colleague)).when(accountService)
                .searchList(argThat((Filters f) -> f != null && f.toString().contains("\"" + MOBILE + "\"")));

        inThisTenant(() -> assertThatThrownBy(() -> accountService.reviveMembership(PERSON, NEW_EMAIL, MOBILE))
                .isInstanceOf(BusinessException.class)
                .hasMessage("That work mobile already belongs to another account."));

        assertThat(closed.getStatus()).isEqualTo(AccountStatus.DEACTIVATED);
        verify(accountService, never()).updateOne(any(UserAccount.class), anyBoolean());
    }

    @Test
    void onlyAClosedAccountCanBeReHired() {
        UserAccount live = membership(AccountStatus.ACTIVE, OLD_EMAIL);
        doReturn(Optional.of(live)).when(accountService).getById(CLOSED_ROW);

        inThisTenant(() -> assertThatThrownBy(() -> accountService.rehire(CLOSED_ROW))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only a closed account can be re-hired."));

        verify(accountService, never()).reviveMembership(any(), any(), any());
    }

    @Test
    void somebodyElsesRowHoldingTheContactIsStillADuplicate() {
        // The exclusion is for the leaver's OWN closed row only — a colleague's live account holding
        // the address is exactly what uk_user_account_tenant_email refuses.
        personHasMembershipHere(AccountStatus.DEACTIVATED);
        UserAccount colleague = new UserAccount();
        colleague.setId(555L);
        colleague.setTenantId(THIS_TENANT);
        colleague.setEmail(NEW_EMAIL);
        doReturn(List.of(colleague)).when(accountService).searchList(any(Filters.class));

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(NEW_EMAIL, MOBILE, "Ada L"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Email already exists"));

        verify(accountService, never()).reviveMembership(any(), any(), any());
    }
}

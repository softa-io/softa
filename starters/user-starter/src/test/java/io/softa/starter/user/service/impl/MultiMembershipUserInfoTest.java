package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A person is one; their memberships are many. Everything UserInfo says about WHERE the session is
 * must come from the membership that was chosen.
 *
 * <p>Both directions used to run through {@code UserProfile.userId} — a single-valued back-pointer
 * from the 1:1 era that names exactly one account. It made the company picker structurally unable
 * to work: the profile was found only for the account that pointer happened to name, so the second
 * company refused entry with "profile not found"; and had the lookup succeeded, the session would
 * have been built from that same pointer — landing the person in the FIRST company's tenant after
 * they picked the second, with no error anywhere.
 */
class MultiMembershipUserInfoTest {

    private static final Long PROFILE = 7L;
    private static final Long FIRST_ACCOUNT = 100L;
    private static final Long SECOND_ACCOUNT = 200L;

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final UserProfileServiceImpl profileService = spy(new UserProfileServiceImpl());

    /** {@code SystemConfig.env} is a @PostConstruct singleton; a plain unit test has to supply it. */
    private final SystemConfig previousEnv = SystemConfig.env;

    @AfterEach
    void restoreEnv() {
        SystemConfig.env = previousEnv;   // a static — do not leak it into the next test class
    }

    MultiMembershipUserInfoTest() {
        SystemConfig.env = new SystemConfig();
        ReflectionTestUtils.setField(profileService, "accountService", accountService);
        ReflectionTestUtils.setField(profileService, "cacheService", cacheService);
        // Cold cache: every read goes to the database path under test.
        when(cacheService.get(anyString(), any(Class.class))).thenReturn(null);

        UserProfile profile = new UserProfile();
        profile.setId(PROFILE);
        // The legacy back-pointer names the FIRST account only — the shape every multi-company
        // person has, since one person keeps one profile however many tenants they join.
        profile.setUserId(FIRST_ACCOUNT);
        profile.setFullName("Ada");
        doReturn(Optional.of(profile)).when(profileService).getById(PROFILE);

        when(accountService.getById(FIRST_ACCOUNT))
                .thenReturn(Optional.of(membership(FIRST_ACCOUNT, 1L, AccountStatus.ACTIVE)));
        when(accountService.getById(SECOND_ACCOUNT))
                .thenReturn(Optional.of(membership(SECOND_ACCOUNT, 2L, AccountStatus.ACTIVE)));
    }

    private static UserAccount membership(Long accountId, Long tenantId, AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(accountId);
        account.setTenantId(tenantId);
        account.setProfileId(PROFILE);
        account.setStatus(status);
        return account;
    }

    @Test
    void theSecondCompanyIsReachableAtAll() {
        // The reported failure: clicking the second company answered "User profile not found".
        assertThat(profileService.getUserInfo(SECOND_ACCOUNT)).isNotNull();
    }

    @Test
    void theSessionLandsInTheCompanyThatWasChosen() {
        // The dangerous half. Building from the back-pointer would put tenant 1 here.
        UserInfo userInfo = profileService.getUserInfo(SECOND_ACCOUNT);

        assertThat(userInfo.getTenantId()).isEqualTo(2L);
        assertThat(userInfo.getUserId()).isEqualTo(SECOND_ACCOUNT);
    }

    @Test
    void theFirstCompanyStillResolvesToItself() {
        UserInfo userInfo = profileService.getUserInfo(FIRST_ACCOUNT);

        assertThat(userInfo.getTenantId()).isEqualTo(1L);
        assertThat(userInfo.getUserId()).isEqualTo(FIRST_ACCOUNT);
    }

    @Test
    void statusIsReadFromTheChosenMembership() {
        // Frozen in company 2, active in company 1. ContextBuilder force-logs-out on this flag, so
        // reading it from the wrong account keeps a frozen membership alive.
        when(accountService.getById(SECOND_ACCOUNT))
                .thenReturn(Optional.of(membership(SECOND_ACCOUNT, 2L, AccountStatus.FROZEN)));

        assertThat(profileService.getUserInfo(SECOND_ACCOUNT).getActive()).isFalse();
        assertThat(profileService.getUserInfo(FIRST_ACCOUNT).getActive()).isTrue();
    }

    @Test
    void theTenantNameIsTheChosenMembershipsTenantAndIsCached() {
        TenantInfoService tenants = mock(TenantInfoService.class);
        when(tenants.getTenantName(1L)).thenReturn("Acme");
        when(tenants.getTenantName(2L)).thenReturn("Globex");
        ReflectionTestUtils.setField(profileService, "tenantInfoService", tenants);

        assertThat(profileService.getUserInfo(SECOND_ACCOUNT).getTenantName()).isEqualTo("Globex");
        assertThat(profileService.getUserInfo(FIRST_ACCOUNT).getTenantName()).isEqualTo("Acme");
        verify(cacheService).save(eq(RedisConstant.USER_INFO + SECOND_ACCOUNT),
                argThat(saved -> "Globex".equals(((UserInfo) saved).getTenantName())), anyInt());
    }

    @Test
    void aCachedUserInfoReadsNoTenant() {
        // The name is cached with the rest, so the common path — a cache hit — costs no tenant read.
        UserInfo cached = new UserInfo();
        cached.setUserId(SECOND_ACCOUNT);
        cached.setTenantId(2L);
        cached.setTenantName("Globex");
        when(cacheService.get(RedisConstant.USER_INFO + SECOND_ACCOUNT, UserInfo.class)).thenReturn(cached);
        TenantInfoService tenants = mock(TenantInfoService.class);
        ReflectionTestUtils.setField(profileService, "tenantInfoService", tenants);

        assertThat(profileService.getUserInfo(SECOND_ACCOUNT).getTenantName()).isEqualTo("Globex");
        verifyNoInteractions(tenants);
    }

    @Test
    void anAccountWithNoPersonFailsLoudly() {
        UserAccount orphan = membership(SECOND_ACCOUNT, 2L, AccountStatus.ACTIVE);
        orphan.setProfileId(null);
        when(accountService.getById(SECOND_ACCOUNT)).thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> profileService.getUserInfo(SECOND_ACCOUNT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void linkingASecondMembershipLeavesThePersonAlone() {
        // The N:1 link writes ONE pointer: account.profileId. It must not touch the person's
        // UserProfile.userId back-pointer — that names one account and they now have several, so
        // repointing it at the newest would just move the breakage to whichever membership it
        // stopped naming. And it must write no UserIdentity: theirs exists and carries their
        // password, so a second one would split their credentials in two.
        UserAccount second = membership(SECOND_ACCOUNT, 2L, AccountStatus.ACTIVE);
        second.setProfileId(null);
        when(accountService.getById(SECOND_ACCOUNT)).thenReturn(Optional.of(second));

        UserInfo userInfo = profileService.linkAccountToPerson(SECOND_ACCOUNT, PROFILE);

        assertThat(second.getProfileId()).isEqualTo(PROFILE);
        verify(accountService).updateOne(second);
        // Built from the membership that was linked, not from the person's back-pointer.
        assertThat(userInfo.getUserId()).isEqualTo(SECOND_ACCOUNT);
        assertThat(userInfo.getTenantId()).isEqualTo(2L);
        // Never the profile write path — no profile and no identity are minted here.
        verify(profileService, never()).createOne(any(UserProfile.class));
    }

    @Test
    void linkingRefusesAnAccountThatDoesNotExist() {
        when(accountService.getById(SECOND_ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.linkAccountToPerson(SECOND_ACCOUNT, PROFILE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void linkingRefusesAPersonThatDoesNotExist() {
        doReturn(Optional.empty()).when(profileService).getById(PROFILE);

        assertThatThrownBy(() -> profileService.linkAccountToPerson(SECOND_ACCOUNT, PROFILE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void editingTheProfileEvictsEveryMembership() {
        // The cache is keyed per account. Evicting only the back-pointer's account leaves the
        // person's other tenants serving the old name for the cache's month-long TTL — right
        // after they were told the change was saved.
        UserProfile profile = new UserProfile();
        profile.setId(PROFILE);
        profile.setUserId(FIRST_ACCOUNT);
        doReturn(profile).when(profileService).getCurrentUserProfile();
        doReturn(true).when(profileService).updateOne(any(UserProfile.class), anyBoolean());
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(
                membership(FIRST_ACCOUNT, 1L, AccountStatus.ACTIVE),
                membership(SECOND_ACCOUNT, 2L, AccountStatus.ACTIVE)));

        Context ctx = new Context();
        ctx.setUserId(SECOND_ACCOUNT);
        ContextHolder.runWith(ctx, () -> profileService.saveMyProfile(new UserProfileDTO()));

        verify(cacheService).clear(RedisConstant.USER_INFO + FIRST_ACCOUNT);
        verify(cacheService).clear(RedisConstant.USER_INFO + SECOND_ACCOUNT);
    }
}

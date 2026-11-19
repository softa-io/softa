package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import io.softa.starter.user.entity.AppPushDevice;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.web.session.SessionRecord;
import io.softa.framework.web.session.SessionRenewedEvent;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionRevokedEvent;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.web.session.SessionStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Push devices: one token, one account at a time; dropped with the session it registered under, with
 * the account when it is frozen or closed, and only by its own person when named.
 */
class AppPushDeviceServiceImplTest {

    private static final Long ACCOUNT = 100L;
    private static final Long ADA = 7L;
    private static final String TOKEN = "a1b2c3d4".repeat(8);
    private static final String SESSION = "session-1";

    private final SessionStore sessionStore = mock(SessionStore.class);
    private final AppPushDeviceServiceImpl service = spy(new AppPushDeviceServiceImpl());

    AppPushDeviceServiceImplTest() {
        ReflectionTestUtils.setField(service, "sessionStore", sessionStore);
        when(sessionStore.peek(SESSION)).thenReturn(Optional.of(
                new SessionRecord(ACCOUNT, ADA, 1L, "ios", "1.0.0", null, LocalDateTime.now())));
        doReturn(1L).when(service).createOne(any(AppPushDevice.class));
        doReturn(true).when(service).updateOne(any(AppPushDevice.class), anyBoolean());
        doReturn(true).when(service).deleteByIds(anyList());
        doReturn(true).when(service).deleteById(any());
        doReturn(1).when(service).updateByFilter(any(Filters.class), any(AppPushDevice.class));
    }

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A request carrying this account's session, as the APP sends it. */
    private void signedIn(Runnable action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // An APP session is read from X-Session-Id only on a request that says it comes from the APP.
        request.addHeader(SessionStore.APP_PLATFORM_HEADER, "ios");
        request.addHeader(BaseConstant.SESSION_ID_HEADER, SESSION);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Context context = new Context();
        context.setUserId(ACCOUNT);
        context.setTenantId(1L);
        ContextHolder.runWith(context, action);
    }

    private void stored(AppPushDevice... devices) {
        doAnswer(inv -> List.of(devices)).when(service).searchList(any(Filters.class));
    }

    @Test
    void aNewToken_isRegisteredToTheAccountSignedIn_underItsSession() {
        stored();

        signedIn(() -> service.register("iOS", TOKEN, "1.0.0", "iPhone15,2", "en-SG"));

        ArgumentCaptor<AppPushDevice> saved = ArgumentCaptor.forClass(AppPushDevice.class);
        verify(service).createOne(saved.capture());
        assertThat(saved.getValue().getPlatform()).isEqualTo("ios");
        assertThat(saved.getValue().getAccountId()).isEqualTo(ACCOUNT);
        assertThat(saved.getValue().getProfileId()).isEqualTo(ADA);
        assertThat(saved.getValue().getTenantId()).isEqualTo(1L);
        // The session is kept as a digest only: the id itself is a bearer credential.
        assertThat(saved.getValue().getSessionKey())
                .isEqualTo(AppPushDeviceServiceImpl.sessionKey(SESSION)).isNotEqualTo(SESSION);
    }

    @Test
    void aTokenHeldByAnotherAccount_movesToThisOne() {
        AppPushDevice existing = new AppPushDevice();
        existing.setId(5L);
        existing.setDeviceToken(TOKEN);
        existing.setTenantId(1L);
        existing.setAccountId(999L);
        existing.setProfileId(42L);
        stored(existing);

        signedIn(() -> service.register("android", TOKEN, "1.0.1", null, null));

        verify(service, never()).createOne(any(AppPushDevice.class));
        verify(service).updateOne(existing, false);
        assertThat(existing.getAccountId()).isEqualTo(ACCOUNT);
        assertThat(existing.getProfileId()).isEqualTo(ADA);
        assertThat(existing.getLastSeenAt()).isNotNull();
    }

    @Test
    void aTokenMovingToAnotherCompany_isReplaced_notUpdated_soItsTenantMovesToo() {
        // The tenant of a multi-tenant row is left out of every update: updating would leave the
        // row under the old company with the new company's account on it.
        AppPushDevice elsewhere = new AppPushDevice();
        elsewhere.setId(5L);
        elsewhere.setDeviceToken(TOKEN);
        elsewhere.setTenantId(2L);
        elsewhere.setAccountId(999L);
        elsewhere.setProfileId(ADA);
        stored(elsewhere);

        signedIn(() -> service.register("ios", TOKEN, "1.0.1", null, null));

        verify(service).deleteById(5L);
        verify(service, never()).updateOne(any(AppPushDevice.class), anyBoolean());
        ArgumentCaptor<AppPushDevice> saved = ArgumentCaptor.forClass(AppPushDevice.class);
        verify(service).createOne(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(1L);
        assertThat(saved.getValue().getAccountId()).isEqualTo(ACCOUNT);
        assertThat(saved.getValue().getId()).isNull();
    }

    @Test
    void twoRegistrationsOfOneTokenAtOnce_theLoserUpdatesInsteadOfFailing() {
        AppPushDevice raced = new AppPushDevice();
        raced.setId(5L);
        raced.setDeviceToken(TOKEN);
        raced.setTenantId(1L);
        // First read: not there yet. Then the other request's insert wins the unique index.
        doReturn(List.of()).doReturn(List.of(raced)).when(service).searchList(any(Filters.class));
        doThrow(new DuplicateKeyException("uk_app_push_device_token")).when(service).createOne(any(AppPushDevice.class));

        signedIn(() -> service.register("ios", TOKEN, "1.0.1", null, null));

        verify(service).updateOne(raced, false);
        assertThat(raced.getAccountId()).isEqualTo(ACCOUNT);
    }

    @Test
    void movingATokenToAnotherCompany_happensInOneTransaction_andTheRetryInAFreshOne() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        AppPushDevice elsewhere = new AppPushDevice();
        elsewhere.setId(5L);
        elsewhere.setDeviceToken(TOKEN);
        elsewhere.setTenantId(2L);
        // The delete and the first insert in one transaction; the insert loses the race, and the retry is its own.
        doReturn(List.of(elsewhere)).doReturn(List.of()).when(service).searchList(any(Filters.class));
        doThrow(new DuplicateKeyException("uk_app_push_device_token")).doReturn(1L)
                .when(service).createOne(any(AppPushDevice.class));

        signedIn(() -> service.register("ios", TOKEN, "1.0.1", null, null));

        ArgumentCaptor<TransactionDefinition> opened = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, org.mockito.Mockito.times(2)).getTransaction(opened.capture());
        assertThat(opened.getAllValues()).allMatch(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactions).rollback(any());
        verify(transactions).commit(any());
    }

    @Test
    void aRenewedSession_keepsItsDevicesAlive_byTouchingOnlyLastSeen() {
        service.onSessionRenewed(new SessionRenewedEvent(SESSION,
                new SessionRecord(ACCOUNT, ADA, 1L, "ios", "1.0.0", null, LocalDateTime.now())));

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        ArgumentCaptor<AppPushDevice> values = ArgumentCaptor.forClass(AppPushDevice.class);
        verify(service).updateByFilter(filters.capture(), values.capture());
        // Only rows still under this session: one handed to another account in between is left alone.
        assertThat(filters.getValue().toString()).contains(AppPushDeviceServiceImpl.sessionKey(SESSION));
        AppPushDevice written = values.getValue();
        assertThat(written.getLastSeenAt()).isAfter(LocalDateTime.now().minusMinutes(1));
        assertThat(written.getAccountId()).isNull();
        assertThat(written.getSessionKey()).isNull();
        assertThat(written.getTenantId()).isNull();
        assertThat(written.getDeviceToken()).isNull();
        verify(service, never()).updateOne(any(AppPushDevice.class), anyBoolean());
        verify(service, never()).searchList(any(Filters.class));
    }

    @Test
    void aRenewalRacingANewRegistrationOnTheDevice_cannotGiveTheTokenBack() {
        // B signed in on A's device and registered: the row is B's, under B's session. A's late renewal must match
        // nothing, so it is a conditional update on A's session, never a read-and-write of the row.
        service.onSessionRenewed(new SessionRenewedEvent("session-of-a",
                new SessionRecord(1L, 2L, 1L, "ios", "1.0.0", null, LocalDateTime.now())));

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        verify(service).updateByFilter(filters.capture(), any(AppPushDevice.class));
        assertThat(filters.getValue().toString()).contains(AppPushDeviceServiceImpl.sessionKey("session-of-a"))
                .doesNotContain(AppPushDeviceServiceImpl.sessionKey(SESSION));
    }

    @Test
    void theSessionKeyIsTheSameHexDigestRowsWereStoredWith() throws Exception {
        // Rows registered before this class hashed with HashUtils were keyed by a hand-rolled SHA-256 in lowercase
        // hex; both must give the same key, or every existing registration would stop matching its session.
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(SESSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(AppPushDeviceServiceImpl.sessionKey(SESSION)).isEqualTo(java.util.HexFormat.of().formatHex(digest));
    }

    @Test
    void aTokenNotShapedLikeItsPlatforms_isRefused() {
        assertThatThrownBy(() -> signedIn(() -> service.register("ios", "fcm:token_from-android", null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> signedIn(() -> service.register("ios", "a1b2 c3d4".repeat(8), null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> signedIn(() -> service.register("android", "bad/token?x", null, null, null)))
                .isInstanceOf(BusinessException.class);
        verify(service, never()).createOne(any(AppPushDevice.class));
    }

    @Test
    void anAndroidToken_isRegistered() {
        stored();
        signedIn(() -> service.register("android", "fGx1:APA91b_abc-DEF", null, null, null));

        verify(service).createOne(any(AppPushDevice.class));
    }

    @Test
    void sendersSee_onlyDevicesSeenWithinASessionsLifetime_andTheRestAreDropped() {
        AppPushDevice stale = new AppPushDevice();
        stale.setId(6L);
        stored(stale);

        service.liveDevices(List.of(ACCOUNT));
        assertThat(service.dropStale()).isEqualTo(1);

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        verify(service, org.mockito.Mockito.times(2)).searchList(filters.capture());
        assertThat(filters.getAllValues().get(0).toString()).contains("accountId").contains("lastSeenAt").contains(">=");
        assertThat(filters.getAllValues().get(1).toString()).contains("lastSeenAt").contains("<");
        verify(service).deleteByIds(List.of(6L));
        // A session lives seven days past its last renewal; older than that, it has ended.
        assertThat(AppPushDeviceServiceImpl.staleBefore())
                .isBetween(LocalDateTime.now().minusDays(7).minusMinutes(1), LocalDateTime.now().minusDays(7).plusMinutes(1));
    }

    @Test
    void anUnknownPlatform_isRefused() {
        assertThatThrownBy(() -> signedIn(() -> service.register("symbian", TOKEN, null, null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void namingAToken_dropsItOnlyForItsOwnPerson() {
        stored();

        signedIn(() -> service.unregisterMine(TOKEN));

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        verify(service).searchList(filters.capture());
        assertThat(filters.getValue().toString()).contains("deviceToken").contains("profileId").contains("7");
    }

    @Test
    void anEndedSession_takesItsDeviceWithIt() {
        AppPushDevice device = new AppPushDevice();
        device.setId(5L);
        stored(device);

        service.onSessionRevoked(new SessionRevokedEvent(SESSION,
                new SessionRecord(ACCOUNT, ADA, 1L, "ios", "1.0.0", null, LocalDateTime.now()),
                SessionRevokeReason.KICKED_BY_OTHER_DEVICE));

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        verify(service).searchList(filters.capture());
        assertThat(filters.getValue().toString()).contains(AppPushDeviceServiceImpl.sessionKey(SESSION));
        verify(service).deleteByIds(List.of(5L));
    }

    @Test
    void aFrozenOrClosedAccount_dropsAllItsDevices() {
        AppPushDevice phone = new AppPushDevice();
        phone.setId(5L);
        AppPushDevice tablet = new AppPushDevice();
        tablet.setId(6L);
        stored(phone, tablet);

        service.unregisterAccount(ACCOUNT);

        verify(service).deleteByIds(List.of(5L, 6L));
    }

    @Test
    void aTokenTheServiceDisowns_isDroppedOnlyWhileItIsStillThisAccounts() {
        AppPushDevice phone = new AppPushDevice();
        phone.setId(5L);
        stored(phone);

        service.dropInvalid(TOKEN, ACCOUNT);

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        verify(service).searchList(filters.capture());
        assertThat(filters.getValue().toString()).contains(TOKEN).contains(String.valueOf(ACCOUNT));
        verify(service).deleteByIds(List.of(5L));
    }

    @Test
    void aFailedDrop_neverFailsWhatAskedForIt() {
        doAnswer(inv -> {
            throw new IllegalStateException("db down");
        }).when(service).searchList(any(Filters.class));

        service.unregisterPerson(ADA);
    }
}

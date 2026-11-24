package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.web.session.SessionRecord;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionRevokedEvent;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserLoginHistory;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.LoginChannel;
import io.softa.starter.user.enums.LoginDeviceType;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.enums.LoginMethod;
import io.softa.starter.user.enums.LoginStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserLoginHistoryService;
import io.softa.starter.user.util.ClientInfo;

/**
 * Writes {@code user_login_history}: sign-in outcomes, lock changes, the first password, and every
 * session that ended (from {@link SessionRevokedEvent}).
 *
 * <p>Never fails its caller. A history row is evidence about a login, not part of it: a write that
 * fails is logged and the login goes on.
 */
@Slf4j
@Component
public class LoginHistoryRecorder {

    @Autowired
    private UserLoginHistoryService historyService;

    /** Events about the person's password rather than about one company. */
    private static final Set<LoginEvent> CREDENTIAL_EVENTS =
            EnumSet.of(LoginEvent.LOGIN_FAILED, LoginEvent.LOCKED, LoginEvent.UNLOCKED);

    /**
     * Failures that tried no credential, so can be repeated without limit: kept once, never fanned out. A
     * verification code says so itself ({@link #recordUntried}): CODE_BLOCKED is both the guess that brought a ban
     * on, which is fanned out, and a refusal while it stands, which is not.
     */
    private static final Set<String> REFUSED_UNTRIED = Set.of(AccountErrorCodes.PASSWORD_LOCKED);

    /** Lazy: the account service writes history itself, so it is reached only when a row needs it. */
    @Lazy
    @Autowired
    private UserAccountService accountService;

    /**
     * One row of history, or one per company when no company is involved yet.
     *
     * <p>A failed sign-in, a lock and an unlock happen before a company is chosen, but they are about
     * the person's password, which every company they work in shares. Kept without a tenant they were
     * invisible to every company — the rows were there, and no company's history showed them. So a
     * known person's such event is written once to each company they have joined and not left, under
     * their account there — a consultancy and the platform's own tenant excepted; an identifier that
     * is nobody's, or a person with no such company, names none and stays with the platform.
     *
     * @param accountId the account involved, or null before a company is chosen
     * @param tenantId  that account's tenant, or null
     */
    public void record(LoginEvent event, Long profileId, Long accountId, Long tenantId,
                       LoginChannel channel, LoginMethod method, String failureReason) {
        this.record(event, profileId, accountId, tenantId, channel, method, failureReason, false);
    }

    /**
     * A failed sign-in refused before its credential was tried — a code refused while verification is banned,
     * with none sent, or with none there to match. Kept once, with the platform, like a refusal while the password
     * is locked: see {@link #companiesOf}.
     */
    public void recordUntried(Long profileId, LoginChannel channel, LoginMethod method, String failureReason) {
        this.record(LoginEvent.LOGIN_FAILED, profileId, null, null, channel, method, failureReason, true);
    }

    private void record(LoginEvent event, Long profileId, Long accountId, Long tenantId,
                        LoginChannel channel, LoginMethod method, String failureReason, boolean untried) {
        ClientInfo client = ClientInfo.current();
        for (UserAccount company : this.companiesOf(event, profileId, accountId, tenantId, failureReason, untried)) {
            UserLoginHistory row = baseRow(event, profileId, company.getId(), company.getTenantId(),
                    client.platform(), client.appVersion(), client.deviceModel(), client.osVersion());
            row.setChannel(channel);
            row.setLoginMethod(method);
            row.setFailureReason(StringUtils.abbreviate(failureReason, 255));
            row.setStatus(failureReason == null ? LoginStatus.SUCCESS : LoginStatus.INVALID);
            this.write(row);
        }
    }

    /**
     * Where a row goes: the company named, or — none named, the person known, and the event about
     * their password — each company they have joined and not left; otherwise a single row without a
     * company. A sign-in that stopped at the company picker is not fanned out: it entered none of them.
     *
     * <p>Nor is an attempt refused before the credential was tried: while the password is locked, while
     * codes are blocked, or with no code there to match. It changes nothing — a lock or a block was
     * already written to each company — and anyone who knows the identifier can repeat it without end;
     * fanned out, every such attempt would be multiplied into every company's history. It is kept once,
     * with the platform.
     */
    private List<UserAccount> companiesOf(LoginEvent event, Long profileId, Long accountId, Long tenantId,
                                          String failureReason, boolean untried) {
        boolean refusedUntried = event == LoginEvent.LOGIN_FAILED
                && (untried || failureReason != null && REFUSED_UNTRIED.contains(failureReason));
        if (tenantId == null && profileId != null && CREDENTIAL_EVENTS.contains(event) && !refusedUntried) {
            List<UserAccount> joined = new ArrayList<>();
            try {
                for (UserAccount membership : accountService.listMembershipsOf(profileId)) {
                    if (employedThere(membership) && joinedAndNotLeft(membership.getStatus())) {
                        joined.add(membership);
                    }
                }
            } catch (RuntimeException e) {
                // History never fails its caller: unattributed is better than unwritten.
                log.warn("Companies of profile {} not read for login history: {}", profileId, e.getMessage());
            }
            if (!joined.isEmpty()) {
                return joined;
            }
        }
        UserAccount named = new UserAccount();
        named.setId(accountId);
        named.setTenantId(tenantId);
        return List.of(named);
    }

    /**
     * A company the person works in: not a consultancy, whose customer has no business seeing how the consultant
     * signs in, and not the platform's own tenant.
     */
    private static boolean employedThere(UserAccount membership) {
        return membership.getTenantId() != null
                && !BaseConstant.PLATFORM_TENANT_ID.equals(membership.getTenantId())
                && !Boolean.TRUE.equals(membership.getConsultant());
    }

    private static boolean joinedAndNotLeft(AccountStatus status) {
        return status == AccountStatus.ACTIVE || status == AccountStatus.FROZEN || status == AccountStatus.LOCKED;
    }

    /** A switch from one tenant to another; both accounts are the same person's. */
    public void recordSwitch(Long profileId, Long fromAccountId, Long toAccountId, Long toTenantId) {
        ClientInfo client = ClientInfo.current();
        UserLoginHistory row = baseRow(LoginEvent.TENANT_SWITCHED, profileId, toAccountId, toTenantId,
                client.platform(), client.appVersion(), client.deviceModel(), client.osVersion());
        row.setFromAccountId(fromAccountId);
        row.setStatus(LoginStatus.SUCCESS);
        this.write(row);
    }

    @EventListener
    public void onSessionRevoked(SessionRevokedEvent event) {
        LoginEvent loginEvent = switch (event.reason()) {
            case LOGOUT -> LoginEvent.LOGOUT;
            case ORG_UNAVAILABLE -> LoginEvent.ORG_UNAVAILABLE;
            case RESET_USER, UNBOUND -> LoginEvent.RESET_USER;
            case KICKED_BY_OTHER_DEVICE, PASSWORD_RESET -> LoginEvent.KICKED;
            // Replaced by a new session the switch itself records; nothing ended for the person.
            case SWITCHED_TENANT, LEFT_TENANT -> null;
        };
        if (loginEvent == null) {
            return;
        }
        SessionRecord session = event.session();
        // The revoked session's own device, not the caller's: a kick is issued from another device.
        UserLoginHistory row = baseRow(loginEvent, session.profileId(), session.accountId(), session.tenantId(),
                session.platform(), session.appVersion(), session.deviceModel(), session.osVersion());
        row.setStatus(LoginStatus.SUCCESS);
        row.setFailureReason(event.reason() == SessionRevokeReason.PASSWORD_RESET
                ? "Password reset" : null);
        this.write(row);
    }

    private static UserLoginHistory baseRow(LoginEvent event, Long profileId, Long accountId, Long tenantId,
                                            String platform, String appVersion, String deviceModel,
                                            String osVersion) {
        ClientInfo client = ClientInfo.current();
        UserLoginHistory row = new UserLoginHistory();
        row.setEvent(event);
        row.setProfileId(profileId);
        row.setUserId(accountId);
        row.setTenantId(tenantId);
        row.setPlatform(platform);
        row.setAppVersion(appVersion);
        row.setDeviceModel(deviceModel);
        row.setOsVersion(osVersion);
        row.setLoginDeviceType(platform == null ? LoginDeviceType.WEB_BROWSER : LoginDeviceType.MOBILE_APP);
        row.setIpAddress(client.ip());
        row.setUserAgent(client.userAgent());
        return row;
    }

    private void write(UserLoginHistory row) {
        try {
            Long tenantId = row.getTenantId();
            boolean inThatTenant = ContextHolder.existContext()
                    && Objects.equals(ContextHolder.getContext().getTenantId(), tenantId);
            if (tenantId != null && !inThatTenant) {
                // A row for a company the caller is not in: a failure fanned out before any company was
                // chosen, or a session ended from outside it. The write refuses a row of another tenant
                // than the context's, so it is made as that company, past the caller's permissions —
                // in a context of its own, carrying nothing of the caller's: not their user, roles or
                // company grants, and no flows started by a write made on nobody's behalf. Only the trace id,
                // so the row can be followed back to the request it came from.
                Context context = ContextHolder.existContext()
                        ? new Context(ContextHolder.getContext().getTraceId()) : new Context();
                context.setTenantId(tenantId);
                context.setSkipPermissionCheck(true);
                context.setTriggerFlow(false);
                ContextHolder.runWith(context, () -> historyService.record(row));
            } else if (ContextHolder.existContext()) {
                historyService.record(row);
            } else {
                // Reached from the request filter before any context is bound (a session ended by
                // the per-request gate): history is written outside any tenant or user.
                Context context = new Context();
                context.setCrossTenant(true);
                context.setSkipPermissionCheck(true);
                ContextHolder.runWith(context, () -> historyService.record(row));
            }
        } catch (RuntimeException e) {
            log.warn("Login history not written for event {} of profile {}: {}",
                    row.getEvent(), row.getProfileId(), e.getMessage());
        }
    }
}

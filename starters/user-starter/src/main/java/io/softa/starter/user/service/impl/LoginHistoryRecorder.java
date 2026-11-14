package io.softa.starter.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.web.session.SessionRecord;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionRevokedEvent;
import io.softa.starter.user.entity.UserLoginHistory;
import io.softa.starter.user.enums.LoginChannel;
import io.softa.starter.user.enums.LoginDeviceType;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.enums.LoginMethod;
import io.softa.starter.user.enums.LoginStatus;
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

    /**
     * One row of history.
     *
     * @param accountId the account involved, or null before a company is chosen
     * @param tenantId  that account's tenant, or null
     */
    public void record(LoginEvent event, Long profileId, Long accountId, Long tenantId,
                       LoginChannel channel, LoginMethod method, String failureReason) {
        ClientInfo client = ClientInfo.current();
        UserLoginHistory row = baseRow(event, profileId, accountId, tenantId,
                client.platform(), client.appVersion(), client.deviceModel());
        row.setChannel(channel);
        row.setLoginMethod(method);
        row.setFailureReason(StringUtils.abbreviate(failureReason, 255));
        row.setStatus(failureReason == null ? LoginStatus.SUCCESS : LoginStatus.INVALID);
        this.write(row);
    }

    /** A switch from one tenant to another; both accounts are the same person's. */
    public void recordSwitch(Long profileId, Long fromAccountId, Long toAccountId, Long toTenantId) {
        ClientInfo client = ClientInfo.current();
        UserLoginHistory row = baseRow(LoginEvent.TENANT_SWITCHED, profileId, toAccountId, toTenantId,
                client.platform(), client.appVersion(), client.deviceModel());
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
                session.platform(), session.appVersion(), session.deviceModel());
        row.setStatus(LoginStatus.SUCCESS);
        row.setFailureReason(event.reason() == SessionRevokeReason.PASSWORD_RESET
                ? "Password reset" : null);
        this.write(row);
    }

    private static UserLoginHistory baseRow(LoginEvent event, Long profileId, Long accountId, Long tenantId,
                                            String platform, String appVersion, String deviceModel) {
        ClientInfo client = ClientInfo.current();
        UserLoginHistory row = new UserLoginHistory();
        row.setEvent(event);
        row.setProfileId(profileId);
        row.setUserId(accountId);
        row.setTenantId(tenantId);
        row.setPlatform(platform);
        row.setAppVersion(appVersion);
        row.setDeviceModel(deviceModel);
        row.setLoginDeviceType(platform == null ? LoginDeviceType.WEB_BROWSER : LoginDeviceType.MOBILE_APP);
        row.setIpAddress(client.ip());
        row.setUserAgent(client.userAgent());
        return row;
    }

    private void write(UserLoginHistory row) {
        try {
            if (ContextHolder.existContext()) {
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

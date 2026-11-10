package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.softa.starter.user.entity.AppPushDevice;
import io.softa.starter.user.service.AppPushDeviceService;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.web.session.SessionRecord;
import io.softa.framework.web.session.SessionRenewedEvent;
import io.softa.framework.web.session.SessionRevokedEvent;
import io.softa.framework.web.session.SessionStore;
import io.softa.starter.user.spi.PushDeviceRegistry;


/**
 * Push-device registrations, and the account flows' way of dropping them.
 *
 * <p>Every read and write runs across tenants with the permission check off: a token moves between
 * companies as the person switches, the registration is the caller's own (or the system's to drop),
 * and the model has no row-scope anchor of its own. The tenant on each row is set explicitly.
 */
@Slf4j
@Service
public class AppPushDeviceServiceImpl extends EntityServiceImpl<AppPushDevice, Long>
        implements AppPushDeviceService, PushDeviceRegistry {

    static final Set<String> PLATFORMS = Set.of("ios", "android");

    /**
     * The session the request carries says who is registering: its account, person and company.
     * Read from the session rather than the account service on purpose — the account service drops
     * devices through this class (freeze, off-board), and a dependency back on it is a cycle.
     */
    @Autowired
    private SessionStore sessionStore;

    @Override
    public void register(String platform, String deviceToken, String appVersion, String deviceModel, String locale) {
        String token = StringUtils.trimToNull(deviceToken);
        String os = platform == null ? null : platform.trim().toLowerCase();
        if (token == null || !PLATFORMS.contains(os)) {
            throw new BusinessException("A device token and its platform (ios or android) are required.");
        }
        String sessionId = currentSessionId();
        SessionRecord session = this.currentSession(sessionId);
        if (session == null) {
            throw new BusinessException("Current user not found.");
        }
        AppPushDevice details = new AppPushDevice();
        details.setDeviceToken(token);
        details.setPlatform(os);
        details.setAppVersion(StringUtils.abbreviate(StringUtils.trimToNull(appVersion), 32));
        details.setDeviceModel(StringUtils.abbreviate(StringUtils.trimToNull(deviceModel), 128));
        details.setLocale(StringUtils.abbreviate(StringUtils.trimToNull(locale), 32));
        details.setTenantId(session.tenantId());
        details.setAccountId(session.accountId());
        details.setProfileId(session.profileId());
        details.setSessionKey(sessionKey(sessionId));
        details.setLastSeenAt(LocalDateTime.now());
        acrossTenants(() -> {
            try {
                this.save(details);
            } catch (DuplicateKeyException e) {
                // The APP registered the same token twice at once and the other request inserted it
                // between this one's read and its insert: the row is there now, so this is an update.
                this.save(details);
            }
            return null;
        });
    }

    /** Insert the token, or move its row to these details. */
    private void save(AppPushDevice details) {
        AppPushDevice existing = this.byToken(details.getDeviceToken());
        if (existing != null && !Objects.equals(existing.getTenantId(), details.getTenantId())) {
            // The tenant of a multi-tenant row is fixed once written — an update leaves it out — so a
            // token moving to another company's account would keep the old company under the new
            // account. Replace the row instead.
            this.deleteById(existing.getId());
            existing = null;
        }
        if (existing == null) {
            AppPushDevice fresh = new AppPushDevice();
            copy(details, fresh);
            this.createOne(fresh);
        } else {
            copy(details, existing);
            this.updateOne(existing, false);
        }
    }

    private static void copy(AppPushDevice from, AppPushDevice to) {
        to.setDeviceToken(from.getDeviceToken());
        to.setPlatform(from.getPlatform());
        to.setAppVersion(from.getAppVersion());
        to.setDeviceModel(from.getDeviceModel());
        to.setLocale(from.getLocale());
        to.setTenantId(from.getTenantId());
        to.setAccountId(from.getAccountId());
        to.setProfileId(from.getProfileId());
        to.setSessionKey(from.getSessionKey());
        to.setLastSeenAt(from.getLastSeenAt());
    }

    @Override
    public List<AppPushDevice> liveDevices(Collection<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        return acrossTenants(() -> this.searchList(new Filters()
                .in(AppPushDevice::getAccountId, accountIds)
                .ge(AppPushDevice::getLastSeenAt, staleBefore())));
    }

    @Override
    public void dropInvalid(String deviceToken, Long accountId) {
        if (StringUtils.isBlank(deviceToken) || accountId == null) {
            return;
        }
        this.drop(new Filters().eq(AppPushDevice::getDeviceToken, deviceToken).eq(AppPushDevice::getAccountId, accountId));
    }

    @Override
    public int dropStale() {
        List<Long> ids = acrossTenants(() -> this.searchList(new Filters()
                .lt(AppPushDevice::getLastSeenAt, staleBefore()))
                .stream().map(AppPushDevice::getId).filter(Objects::nonNull).toList());
        if (!ids.isEmpty()) {
            acrossTenants(() -> this.deleteByIds(ids));
        }
        return ids.size();
    }

    /**
     * Last seen before this, the session it registered under has certainly ended: a session lives
     * {@link SessionStore#TTL_SECONDS} after its last renewal, and each renewal — and the
     * registration itself — moves {@code lastSeenAt} on. A session that ended on its own sends no
     * event, so this is what finds it.
     */
    static LocalDateTime staleBefore() {
        return LocalDateTime.now().minusSeconds(SessionStore.TTL_SECONDS);
    }

    @Override
    public void unregisterMine(String deviceToken) {
        SessionRecord session = this.currentSession(currentSessionId());
        this.unregisterDevice(deviceToken, session == null ? null : session.profileId());
    }

    // ── the account flows' side (PushDeviceRegistry) ────────────────

    @Override
    public void unregisterDevice(String deviceToken, Long profileId) {
        String token = StringUtils.trimToNull(deviceToken);
        if (token == null || profileId == null) {
            return;
        }
        // Only the person's own: naming a token is not proof of holding it.
        this.drop(new Filters().eq(AppPushDevice::getDeviceToken, token).eq(AppPushDevice::getProfileId, profileId));
    }

    @Override
    public void unregisterPerson(Long profileId) {
        if (profileId != null) {
            this.drop(new Filters().eq(AppPushDevice::getProfileId, profileId));
        }
    }

    @Override
    public void unregisterAccount(Long accountId) {
        if (accountId != null) {
            this.drop(new Filters().eq(AppPushDevice::getAccountId, accountId));
        }
    }

    /**
     * A session ended — signed out, signed out by a change made elsewhere, or found unusable on its
     * next request — and the device registered under it stops receiving notifications with it.
     */
    @EventListener
    public void onSessionRevoked(SessionRevokedEvent event) {
        String key = sessionKey(event.sessionId());
        if (key != null) {
            this.drop(new Filters().eq(AppPushDevice::getSessionKey, key));
        }
    }

    /**
     * The session the device registered under was renewed, so it lives on and so does the
     * registration. Renewal happens at most about once a day per client, not on every request.
     */
    @EventListener
    public void onSessionRenewed(SessionRenewedEvent event) {
        String key = sessionKey(event.sessionId());
        if (key == null) {
            return;
        }
        try {
            acrossTenants(() -> {
                List<AppPushDevice> devices = this.searchList(new Filters().eq(AppPushDevice::getSessionKey, key));
                LocalDateTime now = LocalDateTime.now();
                devices.forEach(device -> {
                    device.setLastSeenAt(now);
                    this.updateOne(device, false);
                });
                return null;
            });
        } catch (RuntimeException e) {
            // Never fail the request that renewed the session; the registration is only stale sooner.
            log.warn("Push device last-seen not refreshed: {}", e.getMessage());
        }
    }

    private void drop(Filters filters) {
        try {
            acrossTenants(() -> {
                List<Long> ids = this.searchList(filters).stream().map(AppPushDevice::getId)
                        .filter(Objects::nonNull).toList();
                if (!ids.isEmpty()) {
                    this.deleteByIds(ids);
                }
                return null;
            });
        } catch (RuntimeException e) {
            // A registration left behind only means a notification to a device that should no longer
            // get it; it must not fail the sign-out, freeze or reset that asked for the drop.
            log.warn("Push devices not dropped for {}: {}", filters, e.getMessage());
        }
    }

    /** The session this request carries, when it is the signed-in account's own. */
    private SessionRecord currentSession(String sessionId) {
        Long accountId = ContextHolder.existContext() ? ContextHolder.getContext().getUserId() : null;
        return sessionStore.peek(sessionId)
                .filter(session -> accountId != null && accountId.equals(session.accountId()))
                .orElse(null);
    }

    private static String currentSessionId() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? SessionStore.sessionIdOf(attributes.getRequest()) : null;
    }

    private AppPushDevice byToken(String token) {
        return this.searchList(new Filters().eq(AppPushDevice::getDeviceToken, token)).stream()
                .findFirst().orElse(null);
    }

    /**
     * In every tenant, past roles and row scope: a token follows the person between companies, and the registration is
     * the caller's own or the system's to drop. What keeps it to the caller is the code inside, which finds every row
     * from the signed-in session, never from the request.
     */
    private static <T> T acrossTenants(Supplier<T> action) {
        Context context = ContextHolder.cloneContext();
        context.setCrossTenant(true);
        context.setSkipPermissionCheck(true);
        return ContextHolder.callWith(context, action::get);
    }

    static String sessionKey(String sessionId) {
        return StringUtils.isBlank(sessionId) ? null : EncryptUtils.computeSha256(sessionId);
    }

}

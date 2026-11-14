package io.softa.starter.message.mail.support;

import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.mail.entity.AbstractMailSendServer;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.PlatformMailReceiveServer;
import io.softa.starter.message.mail.entity.PlatformMailSendServer;
import io.softa.starter.message.mail.enums.MailServerOwner;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.service.PlatformMailReceiveServerService;
import io.softa.starter.message.mail.service.PlatformMailSendServerService;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Resolves the mail server a send goes out through, or a fetch reads from. The tenant's and the
 * platform's servers live in separate tables and the only crossing is one way:
 * <ul>
 *   <li>a {@code TENANT}-scope send goes out through the tenant's enabled default send server, or is
 *       relayed through the platform's default when the tenant has none;</li>
 *   <li>a {@code PLATFORM}-scope send goes out through the platform's default only — platform mail
 *       never routes through tenant-controlled SMTP;</li>
 *   <li>receiving never crosses: a tenant fetches through its own receive servers, the platform
 *       through its own, and mail belongs to the owner of the server it arrived through.</li>
 * </ul>
 * With multi-tenancy disabled there is one owner and the scope is moot: every send takes the
 * deployment's own default, then the platform table's.
 * <p>
 * A resolved send server comes with its owner, which the send record keeps so that retries replay the
 * same server and bounces are matched by the receive servers of the same owner. A tenant's server is
 * checked against hosts inside the deployment before it is used ({@link TenantMailHostGuard}).
 */
@Slf4j
@Component
public class MailServerDispatcher {

    /** A send server and whose table it is in. */
    public record SendServer(AbstractMailSendServer config, MailServerOwner owner) {}

    @Autowired
    private MailSendServerConfigService sendConfigService;

    @Autowired
    private PlatformMailSendServerService platformSendService;

    @Autowired
    private MailReceiveServerConfigService receiveConfigService;

    @Autowired
    private PlatformMailReceiveServerService platformReceiveService;

    @Autowired
    private MailConfigCache configCache;

    @Autowired
    private PlatformMailConfigCache platformConfigCache;

    @Autowired
    private TenantMailHostGuard hostGuard;

    /**
     * The default send server for {@code scope} (see the class comment). Cached for a few minutes per
     * owner — {@link MailConfigCache} / {@link PlatformMailConfigCache}.
     */
    public SendServer resolveSend(MessageScope scope) {
        // Single-tenant: one owner, so the scope is moot — the deployment's own servers, then the platform's.
        if (scope != MessageScope.PLATFORM || !TenantScopes.multiTenancyEnabled()) {
            MailSendServerConfig own = configCache.getDefault(TenantScopes.currentTenantOrPlatform(),
                    () -> sendConfigService.findDefault().orElse(null));
            if (own != null) {
                return tenantServer(own);
            }
        }
        PlatformMailSendServer platform = platformConfigCache.getDefault(TenantScopes.PLATFORM,
                () -> platformSendService.findDefault().orElse(null));
        if (platform != null) {
            return new SendServer(platform, MailServerOwner.PLATFORM);
        }
        // ERROR on purpose: an ops-actionable deployment gap, surfaced at send
        // time so alerting catches it before users report it.
        log.error("Mail send rejected: no enabled default send server available. scope={}, tenantId={}",
                scope, TenantScopes.currentTenantOrPlatform());
        throw new BusinessException(scope == MessageScope.PLATFORM
                ? "No enabled platform default send server is configured. "
                        + "Ask the platform operator to add and enable one."
                : "No send server is configured for this tenant, and the platform has no default send "
                        + "server to relay its mail. Ask your administrator to add and enable one.");
    }

    /**
     * The send server {@code id} in {@code owner}'s table, enabled or not — a caller's or a template's
     * pin, or the server a prepared record was routed through. A tenant only ever reaches its own.
     */
    public SendServer resolveSendById(MailServerOwner owner, Long id) {
        if (owner == MailServerOwner.PLATFORM) {
            PlatformMailSendServer config = platformConfigCache.getById(id,
                    () -> platformSendService.getById(id).orElse(null));
            if (config == null) {
                throw new BusinessException("Platform mail send server {0} not found.", id);
            }
            return new SendServer(config, MailServerOwner.PLATFORM);
        }
        MailSendServerConfig config = configCache.getById(id,
                () -> sendConfigService.getById(id).orElse(null));
        // The cache is keyed by id alone: a warm entry must still be the current tenant's own.
        if (config == null || (TenantScopes.multiTenancyEnabled()
                && !Objects.equals(config.getTenantId(), TenantScopes.currentTenantOrPlatform()))) {
            throw new BusinessException("Mail send server config with ID {0} not found.", id);
        }
        return tenantServer(config);
    }

    /**
     * Which table {@code id} is in, for an explicit server id with multi-tenancy off, where the one owner's
     * servers may sit in either table: the platform's either has it or it is a tenant's.
     */
    public MailServerOwner ownerOf(Long id) {
        return platformSendService.getById(id).isPresent() ? MailServerOwner.PLATFORM : MailServerOwner.TENANT;
    }

    private SendServer tenantServer(MailSendServerConfig config) {
        hostGuard.requirePublicBeforeConnecting(config.getHost());
        return new SendServer(config, MailServerOwner.TENANT);
    }

    /** The current tenant's default receive server — never the platform's. */
    public MailReceiveServerConfig resolveReceive() {
        return receiveConfigService.findDefault()
                .orElseThrow(() -> new BusinessException(
                        "No receiving mail server is configured for this tenant. "
                        + "Ask your administrator to add and enable one."));
    }

    /** One of the current tenant's receive servers, enabled or not; another owner's id is not found. */
    public MailReceiveServerConfig resolveReceiveById(Long id) {
        return receiveConfigService.getById(id)
                .orElseThrow(() -> new BusinessException(
                        "Mail receive server config with ID {0} not found.", id));
    }

    /** The platform's default receive server. */
    public PlatformMailReceiveServer resolvePlatformReceive() {
        return platformReceiveService.findDefault()
                .orElseThrow(() -> new BusinessException(
                        "No enabled platform receive server is configured."));
    }

    /** One of the platform's receive servers, enabled or not. */
    public PlatformMailReceiveServer resolvePlatformReceiveById(Long id) {
        return platformReceiveService.getById(id)
                .orElseThrow(() -> new BusinessException("Platform mail receive server {0} not found.", id));
    }
}

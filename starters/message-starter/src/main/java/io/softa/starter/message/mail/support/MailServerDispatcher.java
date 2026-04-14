package io.softa.starter.message.mail.support;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective mail server config for sending or receiving.
 * <p>
 * Resolution order for both directions:
 * <ol>
 *   <li>Tenant's own default config (ORM auto-filters by current tenant_id)</li>
 *   <li>Platform-level default config (tenant_id = 0, via {@code @CrossTenant})</li>
 *   <li>{@link BusinessException} if neither is found</li>
 * </ol>
 */
@Component
public class MailServerDispatcher {

    @Autowired
    private MailSendServerConfigService sendConfigService;

    @Autowired
    private MailReceiveServerConfigService receiveConfigService;

    /**
     * Resolve the effective sending (SMTP) server config for the current tenant.
     */
    public MailSendServerConfig resolveSend() {
        return sendConfigService.findTenantDefault()
                .or(sendConfigService::findPlatformDefault)
                .orElseThrow(() -> new BusinessException(
                        "No sending mail server config found. "
                        + "Please configure one in MailSendServerConfig."));
    }

    /**
     * Resolve a specific sending config by ID, bypassing dispatch logic.
     */
    public MailSendServerConfig resolveSendById(Long id) {
        return sendConfigService.getById(id)
                .orElseThrow(() -> new BusinessException(
                        "Mail send server config with ID {0} not found.", id));
    }

    /**
     * Resolve the effective receiving (IMAP/POP3) server config for the current tenant.
     */
    public MailReceiveServerConfig resolveReceive() {
        return receiveConfigService.findTenantDefault()
                .or(receiveConfigService::findPlatformDefault)
                .orElseThrow(() -> new BusinessException(
                        "No receiving mail server config found. "
                        + "Please configure one in MailReceiveServerConfig."));
    }

    /**
     * Resolve a specific receiving config by ID, bypassing dispatch logic.
     */
    public MailReceiveServerConfig resolveReceiveById(Long id) {
        return receiveConfigService.getById(id)
                .orElseThrow(() -> new BusinessException(
                        "Mail receive server config with ID {0} not found.", id));
    }
}

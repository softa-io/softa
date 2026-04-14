package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailSendServerConfig;

import java.util.Optional;

/**
 * CRUD service for outgoing mail server configurations.
 */
public interface MailSendServerConfigService extends EntityService<MailSendServerConfig, Long> {

    /**
     * Find the current tenant's default enabled sending config.
     * ORM automatically applies {@code WHERE tenant_id = currentTenantId}.
     */
    Optional<MailSendServerConfig> findTenantDefault();

    /**
     * Find the platform-level default sending config (tenant_id = 0).
     * Uses {@code @CrossTenant} to bypass ORM tenant filtering.
     */
    Optional<MailSendServerConfig> findPlatformDefault();

    /**
     * Test SMTP connectivity and authentication for the config identified by {@code id}.
     */
    ConnectivityTestResultDTO testConnectivity(Long id);

    /**
     * Test connectivity using a transient config object (e.g. before saving).
     */
    ConnectivityTestResultDTO testConnectivity(MailSendServerConfig config);
}

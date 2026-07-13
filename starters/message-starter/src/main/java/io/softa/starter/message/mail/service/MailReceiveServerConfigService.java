package io.softa.starter.message.mail.service;

import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;

/**
 * CRUD service for incoming mail server configurations.
 */
public interface MailReceiveServerConfigService extends EntityService<MailReceiveServerConfig, Long> {

    /**
     * Find the current tenant's default enabled receiving config.
     * ORM automatically applies {@code WHERE tenant_id = currentTenantId}.
     */
    Optional<MailReceiveServerConfig> findTenantDefault();

    /**
     * Find the platform-level default receiving config (tenant_id = 0).
     * Uses {@code @CrossTenant} to bypass ORM tenant filtering.
     */
    Optional<MailReceiveServerConfig> findPlatformDefault();

    /**
     * Load a config by id within the caller's visibility scope: the caller's
     * own tenant plus the platform tier (tenant_id = 0). Scheduled fetch runs
     * against platform-level configs too, which the implicit single-tenant
     * filter would hide — id resolution must go through this method rather
     * than {@code getById}.
     */
    Optional<MailReceiveServerConfig> findVisibleById(Long id);

    /**
     * Test IMAP/POP3 connectivity and authentication for the config identified by {@code id}.
     */
    ConnectivityTestResultDTO testConnectivity(Long id);

    /**
     * Test connectivity using a transient config object (e.g. before saving).
     */
    ConnectivityTestResultDTO testConnectivity(MailReceiveServerConfig config);
}

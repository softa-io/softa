package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;

import java.util.Optional;

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
     * Test IMAP/POP3 connectivity and authentication for the config identified by {@code id}.
     */
    ConnectivityTestResultDTO testConnectivity(Long id);

    /**
     * Test connectivity using a transient config object (e.g. before saving).
     */
    ConnectivityTestResultDTO testConnectivity(MailReceiveServerConfig config);
}

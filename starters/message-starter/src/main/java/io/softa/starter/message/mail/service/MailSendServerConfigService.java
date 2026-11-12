package io.softa.starter.message.mail.service;

import io.softa.starter.message.mail.entity.MailSendServerConfig;

/**
 * CRUD service for a tenant's own outgoing mail servers. Multi-tenant: every read and write is the
 * current tenant's. A tenant with no enabled default here has its mail relayed through the platform's
 * ({@link PlatformMailSendServerService}).
 */
public interface MailSendServerConfigService extends MailSendServerService<MailSendServerConfig> {
}

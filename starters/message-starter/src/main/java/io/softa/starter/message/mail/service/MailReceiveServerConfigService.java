package io.softa.starter.message.mail.service;

import io.softa.starter.message.mail.entity.MailReceiveServerConfig;

/**
 * CRUD service for a tenant's own incoming mail servers. Multi-tenant: every read and write is the
 * current tenant's, and a tenant without one has none — never the platform's.
 */
public interface MailReceiveServerConfigService extends MailReceiveServerService<MailReceiveServerConfig> {
}

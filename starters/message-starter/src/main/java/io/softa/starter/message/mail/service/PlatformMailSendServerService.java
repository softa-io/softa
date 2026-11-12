package io.softa.starter.message.mail.service;

import io.softa.starter.message.mail.entity.PlatformMailSendServer;

/**
 * CRUD service for the platform's own outgoing mail servers: they carry the platform's mail and relay
 * the mail of tenants that have no send server of their own.
 */
public interface PlatformMailSendServerService extends MailSendServerService<PlatformMailSendServer> {
}

package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailServerOauth2Config;
import io.softa.starter.message.mail.enums.ServerType;

import java.util.Optional;

/**
 * CRUD service for {@link MailServerOauth2Config}.
 * <p>
 * Each (server_config_id, server_type) pair has at most one OAuth2 credential record,
 * enforced by the unique index {@code uk_server (server_config_id, server_type)}.
 */
public interface MailServerOauth2ConfigService extends EntityService<MailServerOauth2Config, Long> {

    /**
     * Find the OAuth2 credential for a given server config.
     *
     * @param serverConfigId FK to mail_send_server_config or mail_receive_server_config
     * @param serverType     {@link ServerType#SEND} or {@link ServerType#RECEIVE}
     * @return the credential if present
     */
    Optional<MailServerOauth2Config> findByServerConfig(Long serverConfigId, ServerType serverType);
}

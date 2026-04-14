package io.softa.starter.message.mail.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailServerOauth2Token;

import java.util.Optional;

/**
 * CRUD service for {@link MailServerOauth2Token}.
 * <p>
 * Tokens are stored per (server_config_id, account_identifier) pair.
 * Token refresh and exchange are delegated to the application layer.
 */
public interface MailServerOauth2TokenService extends EntityService<MailServerOauth2Token, Long> {

    /**
     * Find the stored token for a given server config and email account.
     *
     * @param serverConfigId    FK to mail_server_oauth2_config.id
     * @param accountIdentifier email address used to authenticate
     * @return the token record if present
     */
    Optional<MailServerOauth2Token> findByAccount(Long serverConfigId, String accountIdentifier);
}

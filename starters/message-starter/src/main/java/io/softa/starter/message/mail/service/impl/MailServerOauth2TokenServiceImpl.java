package io.softa.starter.message.mail.service.impl;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.entity.MailServerOauth2Token;
import io.softa.starter.message.mail.service.MailServerOauth2TokenService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Implementation of {@link MailServerOauth2TokenService}.
 */
@Service
public class MailServerOauth2TokenServiceImpl extends EntityServiceImpl<MailServerOauth2Token, Long>
        implements MailServerOauth2TokenService {

    @Override
    public Optional<MailServerOauth2Token> findByAccount(Long serverConfigId, String accountIdentifier) {
        return searchOne(new Filters()
                .eq(MailServerOauth2Token::getServerConfigId, serverConfigId)
                .eq(MailServerOauth2Token::getAccountIdentifier, accountIdentifier));
    }
}

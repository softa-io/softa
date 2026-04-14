package io.softa.starter.message.mail.service.impl;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.entity.MailServerOauth2Config;
import io.softa.starter.message.mail.enums.ServerType;
import io.softa.starter.message.mail.service.MailServerOauth2ConfigService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Implementation of {@link MailServerOauth2ConfigService}.
 */
@Service
public class MailServerOauth2ConfigServiceImpl extends EntityServiceImpl<MailServerOauth2Config, Long>
        implements MailServerOauth2ConfigService {

    @Override
    public Optional<MailServerOauth2Config> findByServerConfig(Long serverConfigId, ServerType serverType) {
        return searchOne(new Filters()
                .eq(MailServerOauth2Config::getServerConfigId, serverConfigId)
                .eq(MailServerOauth2Config::getServerType, serverType));
    }
}

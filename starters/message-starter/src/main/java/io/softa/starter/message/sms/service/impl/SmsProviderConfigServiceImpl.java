package io.softa.starter.message.sms.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.service.SmsProviderConfigService;

/**
 * Implementation of {@link SmsProviderConfigService}.
 */
@Service
public class SmsProviderConfigServiceImpl extends EntityServiceImpl<SmsProviderConfig, Long>
        implements SmsProviderConfigService {
}

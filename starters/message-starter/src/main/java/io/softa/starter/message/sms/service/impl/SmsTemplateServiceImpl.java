package io.softa.starter.message.sms.service.impl;

import java.util.Map;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.service.SmsTemplateService;

/**
 * Implementation of {@link SmsTemplateService}.
 * <p>
 * SMS templates are platform-owned and not multi-tenant: a code is unique
 * across the table and resolves the same way from any context.
 */
@Service
public class SmsTemplateServiceImpl extends EntityServiceImpl<SmsTemplate, Long>
        implements SmsTemplateService {

    @Override
    public SmsTemplate resolve(String code) {
        // active = true is appended by the framework's active control.
        return searchOne(new Filters().eq(SmsTemplate::getCode, code))
                .orElseThrow(() -> new BusinessException("No SMS template found for code ''{0}''.", code));
    }

    @Override
    public String renderContent(SmsTemplate template, Map<String, Object> variables) {
        return PlaceholderUtils.replacePlaceholders(template.getContent(), variables);
    }
}

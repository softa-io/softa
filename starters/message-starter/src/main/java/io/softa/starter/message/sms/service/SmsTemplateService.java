package io.softa.starter.message.sms.service;

import java.util.Map;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsTemplate;

/**
 * CRUD and resolution service for {@link SmsTemplate}.
 * <p>
 * SMS templates are platform-owned and exist once, for every tenant, so a code
 * resolves to the same template whatever the send's scope.
 */
public interface SmsTemplateService extends EntityService<SmsTemplate, Long> {

    /**
     * Resolve the <b>active</b> template for {@code code}.
     *
     * @param code template code, e.g. {@code "user.verification-code"}
     * @return the resolved template
     * @throws io.softa.framework.base.exception.BusinessException if no active template has the code
     */
    SmsTemplate resolve(String code);

    /**
     * Render the content of {@code template} by substituting {@code variables}
     * into {@code {{ key }}} placeholders.
     */
    String renderContent(SmsTemplate template, Map<String, Object> variables);
}

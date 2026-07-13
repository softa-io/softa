package io.softa.starter.message.sms.service;

import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsTemplate;

/**
 * CRUD and resolution service for {@link SmsTemplate}.
 * <p>
 * Template resolution prefers a tenant template, falling back to the
 * platform-defined default:
 * <pre>
 *   tenant template → platform template (tenant_id = 0) → BusinessException
 * </pre>
 */
public interface SmsTemplateService extends EntityService<SmsTemplate, Long> {

    /**
     * Resolve the matching template for {@code code} in the current tenant,
     * falling back to the platform template.
     *
     * @param code template code, e.g. {@code "VERIFY_CODE"}
     * @return the resolved template
     * @throws io.softa.framework.base.exception.BusinessException if no template is found
     */
    SmsTemplate resolve(String code);

    /**
     * Query the platform-level (tenant_id = 0) template, bypassing tenant
     * isolation.
     *
     * @param code template code
     * @return the platform template if found
     */
    Optional<SmsTemplate> findPlatformByCode(String code);

    /**
     * Render the content of {@code template} by substituting {@code variables}
     * into {@code {{ key }}} placeholders.
     */
    String renderContent(SmsTemplate template, Map<String, Object> variables);
}

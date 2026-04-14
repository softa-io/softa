package io.softa.starter.message.sms.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * CRUD and resolution service for {@link SmsTemplate}.
 * <p>
 * Template resolution follows a four-level fallback so that platform-defined templates
 * serve as defaults and tenants can override them per language:
 * <pre>
 *   tenant + current language
 *       → tenant + "default"
 *           → platform + current language
 *               → platform + "default"
 *                   → BusinessException
 * </pre>
 */
public interface SmsTemplateService extends EntityService<SmsTemplate, Long> {

    /**
     * Resolve the best matching template for {@code code} given the current tenant
     * and the language from {@code ContextHolder}.
     *
     * @param code template code, e.g. {@code "VERIFY_CODE"}
     * @return the resolved template
     * @throws io.softa.framework.base.exception.BusinessException if no template is found
     */
    SmsTemplate resolve(String code);

    /**
     * Query platform-level (tenant_id = 0) templates, bypassing tenant isolation.
     * Tries {@code language} first, then falls back to {@code "default"}.
     *
     * @param code     template code
     * @param language BCP 47 language tag to try first
     * @return the platform template if found
     */
    Optional<SmsTemplate> findPlatformByCode(String code, String language);

    /**
     * Render the content of {@code template} by substituting {@code variables}
     * into {@code {{ key }}} placeholders.
     */
    String renderContent(SmsTemplate template, Map<String, Object> variables);
}

package io.softa.starter.message.mail.service;

import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailTemplate;

/**
 * CRUD and resolution service for {@link MailTemplate}.
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
public interface MailTemplateService extends EntityService<MailTemplate, Long> {

    /**
     * Resolve the best matching template for {@code code} given the current tenant
     * and the language from {@code ContextHolder}.
     *
     * @param code template code, e.g. {@code "USER_WELCOME"}
     * @return the resolved template
     * @throws io.softa.framework.base.exception.BusinessException if no template is found
     */
    MailTemplate resolve(String code);

    /**
     * Query platform-level (tenant_id = 0) templates, bypassing tenant isolation.
     * Tries {@code language} first, then falls back to {@code "default"}.
     * <p>
     * Annotated with {@code @CrossTenant} in the implementation so that ORM tenant
     * filters are suppressed for the duration of the call.
     *
     * @param code     template code
     * @param language BCP 47 language tag to try first
     * @return the platform template if found
     */
    Optional<MailTemplate> findPlatformByCode(String code, String language);

    /**
     * Render the subject of {@code template} by substituting {@code variables}
     * into {@code {{ key }}} placeholders.
     */
    String renderSubject(MailTemplate template, Map<String, Object> variables);

    /**
     * Render the body of {@code template} by substituting {@code variables}
     * into {@code {{ key }}} placeholders.
     * HTML bodies are rendered via Pebble; Text bodies via simple substitution.
     */
    String renderBody(MailTemplate template, Map<String, Object> variables);
}

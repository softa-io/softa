package io.softa.starter.message.mail.service;

import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailTemplate;

/**
 * CRUD and resolution service for {@link MailTemplate}.
 * <p>
 * Template resolution prefers a tenant template, falling back to the
 * platform-defined default:
 * <pre>
 *   tenant template → platform template (tenant_id = 0) → BusinessException
 * </pre>
 */
public interface MailTemplateService extends EntityService<MailTemplate, Long> {

    /**
     * Resolve the matching template for {@code code} in the current tenant,
     * falling back to the platform template.
     *
     * @param code template code, e.g. {@code "USER_WELCOME"}
     * @return the resolved template
     * @throws io.softa.framework.base.exception.BusinessException if no template is found
     */
    MailTemplate resolve(String code);

    /**
     * Query the platform-level (tenant_id = 0) template, bypassing tenant
     * isolation. Annotated with {@code @CrossTenant} in the implementation so
     * that ORM tenant filters are suppressed for the duration of the call.
     *
     * @param code template code
     * @return the platform template if found
     */
    Optional<MailTemplate> findPlatformByCode(String code);

    /**
     * Render the subject of {@code template} by substituting {@code variables}
     * into {@code {{ key }}} placeholders.
     */
    String renderSubject(MailTemplate template, Map<String, Object> variables);

    /**
     * Render the HTML body of {@code template} by substituting {@code variables}
     * into {@code {{ key }}} placeholders. Returns {@code null} when the template
     * has no HTML body (PLAIN-mode templates).
     */
    String renderBodyHtml(MailTemplate template, Map<String, Object> variables);

    /**
     * Render the plain-text body of {@code template} by substituting {@code variables}
     * into {@code {{ key }}} placeholders. Returns {@code null} when the template
     * has no plain-text body (HTML-only / HTML_WITH_DERIVED_PLAIN templates).
     */
    String renderBodyText(MailTemplate template, Map<String, Object> variables);
}

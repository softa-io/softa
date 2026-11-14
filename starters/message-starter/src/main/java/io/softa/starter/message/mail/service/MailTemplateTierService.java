package io.softa.starter.message.mail.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.dto.MailTemplateVariableDTO;
import io.softa.starter.message.mail.entity.AbstractMailTemplate;

/**
 * What the tenant's and the platform's template services share: resolution within the service's own
 * tier, the authoring tools (preview inputs, variable listing) and rendering. A code lives in one tier
 * only — {@link MailTemplateService} holds the tenant's templates, {@link PlatformMailTemplateService}
 * the platform's — and neither ever falls back to the other.
 */
public interface MailTemplateTierService<T extends AbstractMailTemplate> extends EntityService<T, Long> {

    /**
     * The <b>active</b> template with this code in this tier.
     *
     * @throws io.softa.framework.base.exception.BusinessException if there is none
     */
    T resolve(String code);

    /** The template with this code in this tier, active or not. */
    Optional<T> findAnyByCode(String code);

    /**
     * Like {@link #resolve} but WITHOUT the active-control filter — for read-only authoring tools
     * (preview, variable listing) where a disabled template must stay inspectable so it can be
     * verified before being activated. Delivery paths keep using {@link #resolve}.
     */
    T resolveAny(String code);

    /**
     * Load one template row by id: no code resolution, no active-control filter — the row being
     * edited is the row inspected (editor tooling, and test sends of exactly that row).
     *
     * @throws io.softa.framework.base.exception.BusinessException if the row does not exist
     */
    T getRequiredById(Long id);

    /**
     * The distinct placeholder tokens of the template resolved for {@code code}, in first-appearance
     * order across subject / bodyHtml / bodyText — for variable-input UIs (Send Test / Preview).
     */
    List<MailTemplateVariableDTO> listVariables(String code);

    /** {@link #listVariables(String)} addressed by row id (editor tooling). */
    List<MailTemplateVariableDTO> listVariablesById(Long id);

    /** Render the subject by substituting {@code variables} into {@code {{ key }}} placeholders. */
    String renderSubject(AbstractMailTemplate template, Map<String, Object> variables);

    /** Render the HTML body, or {@code null} when the template has none (PLAIN mode). */
    String renderBodyHtml(AbstractMailTemplate template, Map<String, Object> variables);

    /** Render the plain-text body, or {@code null} when the template has none. */
    String renderBodyText(AbstractMailTemplate template, Map<String, Object> variables);
}

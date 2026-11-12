package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.dto.MailTemplateVariableDTO;
import io.softa.starter.message.mail.entity.AbstractMailTemplate;
import io.softa.starter.message.mail.service.MailTemplateTierService;
import io.softa.starter.message.mail.support.TemplateVariableExtractor;

/**
 * The template logic both tiers share. Each subclass reads its own table only: a tenant's
 * {@code MailTemplate} through the ORM's tenant filter, the platform's {@code PlatformMailTemplate}
 * as a plain table.
 * <p>
 * Rendering deliberately uses two engines. The subject is plain variable substitution
 * ({@code PlaceholderUtils}): a one-line field with no use for control flow, where a missing variable
 * staying visible as {@code {{ name }}} beats being silently blanked. Bodies go through Pebble — the
 * HTML body via {@code renderHtml}, which HTML-escapes every {@code {{ }}} output (a template embeds
 * trusted markup explicitly with {@code | raw}); the plain-text body via {@code render}, since escaping
 * would corrupt text/plain content.
 */
public abstract class AbstractMailTemplateServiceImpl<T extends AbstractMailTemplate>
        extends EntityServiceImpl<T, Long> implements MailTemplateTierService<T> {

    @Override
    public T resolve(String code) {
        // The active-control filter (active = true) is applied automatically.
        return searchOne(new Filters().eq(AbstractMailTemplate::getCode, code))
                .orElseThrow(() -> notFound(code));
    }

    @Override
    public Optional<T> findAnyByCode(String code) {
        return searchOne(inactiveIncluded(new Filters().eq(AbstractMailTemplate::getCode, code)));
    }

    @Override
    public T resolveAny(String code) {
        return findAnyByCode(code).orElseThrow(() -> notFound(code));
    }

    /**
     * A read that deliberately reaches disabled rows too: the model is {@code activeControl}, so
     * {@code active = true} is appended to every plain FlexQuery — authoring tools (preview /
     * variables) must see a disabled row, and an id-addressed {@code getById} already bypasses the
     * filter by construction.
     */
    private static FlexQuery inactiveIncluded(Filters filters) {
        FlexQuery flexQuery = new FlexQuery(filters);
        flexQuery.setFilterControl(FilterControl.bypassActiveControl());
        return flexQuery;
    }

    private static BusinessException notFound(String code) {
        return new BusinessException("No mail template found for code ''{0}''.", code);
    }

    @Override
    public T getRequiredById(Long id) {
        return getById(id).orElseThrow(() -> new BusinessException(
                "No mail template found for id {0}.", id));
    }

    @Override
    public List<MailTemplateVariableDTO> listVariables(String code) {
        // resolveAny: variable inputs must work for a disabled template —
        // authoring tools verify BEFORE enabling.
        return TemplateVariableExtractor.extract(resolveAny(code));
    }

    @Override
    public List<MailTemplateVariableDTO> listVariablesById(Long id) {
        return TemplateVariableExtractor.extract(getRequiredById(id));
    }

    @Override
    public String renderSubject(AbstractMailTemplate template, Map<String, Object> variables) {
        return PlaceholderUtils.replacePlaceholders(template.getSubject(), variables);
    }

    @Override
    public String renderBodyHtml(AbstractMailTemplate template, Map<String, Object> variables) {
        return template.getBodyHtml() == null
                ? null : TemplateEngine.renderHtml(template.getBodyHtml(), variables);
    }

    @Override
    public String renderBodyText(AbstractMailTemplate template, Map<String, Object> variables) {
        return template.getBodyText() == null
                ? null : TemplateEngine.render(template.getBodyText(), variables);
    }
}

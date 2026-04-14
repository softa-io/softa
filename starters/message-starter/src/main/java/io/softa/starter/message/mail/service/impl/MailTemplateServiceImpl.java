package io.softa.starter.message.mail.service.impl;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.service.MailTemplateService;

/**
 * Implementation of {@link MailTemplateService}.
 * <p>
 * Template resolution uses a four-level fallback:
 * <ol>
 *   <li>Tenant template matching the request language</li>
 *   <li>Tenant template with language = {@code "default"}</li>
 *   <li>Platform template (tenant_id = 0) matching the request language</li>
 *   <li>Platform template with language = {@code "default"}</li>
 * </ol>
 * Levels 3–4 run inside {@link #findPlatformByCode} which is annotated
 * with {@code @CrossTenant} to bypass ORM tenant isolation. It is called
 * through the Spring proxy via the {@code self} reference to ensure the
 * AOP advice is applied.
 */
@Service
public class MailTemplateServiceImpl extends EntityServiceImpl<MailTemplate, Long>
        implements MailTemplateService {

    /**
     * Self-reference to allow {@code @CrossTenant} AOP advice to be applied
     * when calling {@link #findPlatformByCode} from within the same bean.
     */
    @Lazy
    @Autowired
    private MailTemplateService self;

    @Override
    public MailTemplate resolve(String code) {
        String lang = ContextHolder.getContext().getLanguage().getCode();

        // 1. Tenant template matching current language
        Optional<MailTemplate> result = searchOne(new Filters()
                .eq(MailTemplate::getCode, code)
                .eq(MailTemplate::getLanguage, lang)
                .eq(MailTemplate::getIsEnabled, true));
        if (result.isPresent()) return result.get();

        // 2. Tenant template with language = "default"
        result = searchOne(new Filters()
                .eq(MailTemplate::getCode, code)
                .eq(MailTemplate::getLanguage, "default")
                .eq(MailTemplate::getIsEnabled, true));
        return result.orElseGet(() -> self.findPlatformByCode(code, lang)
                .orElseThrow(() -> new BusinessException(
                        "No mail template found for code ''{0}'' in language ''{1}'' or ''default''.", code, lang)));

        // 3 & 4. Platform templates (tenant_id = 0) — cross-tenant via proxy
    }

    @Override
    @CrossTenant
    public Optional<MailTemplate> findPlatformByCode(String code, String language) {
        // 3. Platform template matching requested language
        Optional<MailTemplate> result = searchOne(new Filters()
                .eq("tenantId", 0L)
                .eq(MailTemplate::getCode, code)
                .eq(MailTemplate::getLanguage, language)
                .eq(MailTemplate::getIsEnabled, true));
        if (result.isPresent()) return result;

        // 4. Platform template with language = "default"
        return searchOne(new Filters()
                .eq("tenantId", 0L)
                .eq(MailTemplate::getCode, code)
                .eq(MailTemplate::getLanguage, "default")
                .eq(MailTemplate::getIsEnabled, true));
    }

    @Override
    public String renderSubject(MailTemplate template, Map<String, Object> variables) {
        return PlaceholderUtils.replacePlaceholders(template.getSubject(), variables);
    }

    @Override
    public String renderBody(MailTemplate template, Map<String, Object> variables) {
        return TemplateEngine.render(template.getBody(), variables);
    }
}

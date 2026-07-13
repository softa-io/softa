package io.softa.starter.message.mail.service.impl;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

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
 * Template resolution prefers a tenant template, falling back to the platform
 * template (tenant_id = 0). The platform lookup runs inside
 * {@link #findPlatformByCode}, annotated {@code @CrossTenant} to bypass ORM
 * tenant isolation; it is called through the Spring proxy via the {@code self}
 * reference to ensure the AOP advice is applied.
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
        // 1. Tenant template (ORM auto-scopes to the current tenant)
        Optional<MailTemplate> result = searchOne(new Filters()
                .eq(MailTemplate::getCode, code)
                .eq(MailTemplate::getIsEnabled, true));
        return result.orElseGet(() -> self.findPlatformByCode(code)
                .orElseThrow(() -> new BusinessException(
                        "No mail template found for code ''{0}''.", code)));

        // 2. Platform template (tenant_id = 0) — cross-tenant via proxy
    }

    @Override
    @CrossTenant
    public Optional<MailTemplate> findPlatformByCode(String code) {
        return searchOne(new Filters()
                .eq("tenantId", 0L)
                .eq(MailTemplate::getCode, code)
                .eq(MailTemplate::getIsEnabled, true));
    }

    @Override
    public String renderSubject(MailTemplate template, Map<String, Object> variables) {
        return PlaceholderUtils.replacePlaceholders(template.getSubject(), variables);
    }

    @Override
    public String renderBodyHtml(MailTemplate template, Map<String, Object> variables) {
        return template.getBodyHtml() == null
                ? null : TemplateEngine.render(template.getBodyHtml(), variables);
    }

    @Override
    public String renderBodyText(MailTemplate template, Map<String, Object> variables) {
        return template.getBodyText() == null
                ? null : TemplateEngine.render(template.getBodyText(), variables);
    }
}

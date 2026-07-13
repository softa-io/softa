package io.softa.starter.message.sms.service.impl;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.orm.annotation.CrossTenant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.service.SmsTemplateService;

/**
 * Implementation of {@link SmsTemplateService}.
 * <p>
 * Template resolution prefers a tenant template, falling back to the platform
 * template (tenant_id = 0). The platform lookup runs inside
 * {@link #findPlatformByCode}, annotated {@code @CrossTenant} to bypass ORM
 * tenant isolation; it is called through the Spring proxy via the {@code self}
 * reference to ensure the AOP advice is applied.
 */
@Service
public class SmsTemplateServiceImpl extends EntityServiceImpl<SmsTemplate, Long>
        implements SmsTemplateService {

    /**
     * Self-reference to allow {@code @CrossTenant} AOP advice to be applied
     * when calling {@link #findPlatformByCode} from within the same bean.
     */
    @Lazy
    @Autowired
    private SmsTemplateService self;

    @Override
    public SmsTemplate resolve(String code) {
        // 1. Tenant template (ORM auto-scopes to the current tenant)
        Optional<SmsTemplate> result = searchOne(new Filters()
                .eq(SmsTemplate::getCode, code)
                .eq(SmsTemplate::getIsEnabled, true));
        return result.orElseGet(() -> self.findPlatformByCode(code)
                .orElseThrow(() -> new BusinessException(
                        "No SMS template found for code ''{0}''.", code)));

        // 2. Platform template (tenant_id = 0) — cross-tenant via proxy
    }

    @Override
    @CrossTenant
    public Optional<SmsTemplate> findPlatformByCode(String code) {
        return searchOne(new Filters()
                .eq("tenantId", 0L)
                .eq(SmsTemplate::getCode, code)
                .eq(SmsTemplate::getIsEnabled, true));
    }

    @Override
    public String renderContent(SmsTemplate template, Map<String, Object> variables) {
        return PlaceholderUtils.replacePlaceholders(template.getContent(), variables);
    }
}

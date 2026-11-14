package io.softa.starter.message.mail.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.service.MailTemplateService;

/** Implementation of {@link MailTemplateService}: the current tenant's templates. */
@Service
public class MailTemplateServiceImpl extends AbstractMailTemplateServiceImpl<MailTemplate>
        implements MailTemplateService {

    @Override
    public boolean anyTenantHasCode(String code) {
        Context crossTenant = ContextHolder.cloneContext();
        crossTenant.setCrossTenant(true);
        return ContextHolder.callWith(crossTenant,
                () -> count(new Filters().eq(MailTemplate::getCode, code)) > 0);
    }
}

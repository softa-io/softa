package io.softa.starter.message.mail.service;

import io.softa.starter.message.mail.entity.MailTemplate;

/**
 * CRUD and resolution service for a tenant's own {@link MailTemplate}s — what a {@code TENANT}-scope
 * send renders. Multi-tenant: every read is the current tenant's. Tenants receive their rows at
 * provisioning from the application's per-tenant seed files and edit them freely; a code the platform
 * uses is refused on every write ({@code MailTemplateWriteValidator}).
 */
public interface MailTemplateService extends MailTemplateTierService<MailTemplate> {

    /**
     * Whether any tenant holds a template with this code — for the hint a {@code PLATFORM}-scope send
     * of a tenant code fails with. Reads across tenants; answers nothing else.
     */
    boolean anyTenantHasCode(String code);
}

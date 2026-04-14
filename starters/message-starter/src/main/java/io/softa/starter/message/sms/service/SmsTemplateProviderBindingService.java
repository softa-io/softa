package io.softa.starter.message.sms.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;

import java.util.List;

/**
 * CRUD and query service for {@link SmsTemplateProviderBinding}.
 * <p>
 * Provides methods to retrieve the ordered list of provider bindings for
 * a given SMS template, supporting cross-channel failover during send.
 */
public interface SmsTemplateProviderBindingService extends EntityService<SmsTemplateProviderBinding, Long> {

    /**
     * Find all enabled bindings for the given template within the current tenant,
     * ordered by {@code sortOrder} ascending (lowest = highest priority).
     *
     * @param templateId the SMS template ID
     * @return ordered list of enabled bindings, or empty list if none configured
     */
    List<SmsTemplateProviderBinding> findByTemplateId(Long templateId);

    /**
     * Find all enabled platform-level (tenant_id = 0) bindings for the given template,
     * ordered by {@code sortOrder} ascending.
     * <p>
     * Uses {@code @CrossTenant} to bypass ORM tenant filtering.
     *
     * @param templateId the SMS template ID
     * @return ordered list of enabled platform bindings, or empty list if none
     */
    List<SmsTemplateProviderBinding> findPlatformBindingsByTemplateId(Long templateId);
}


package io.softa.starter.message.sms.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;

/**
 * CRUD and query service for {@link SmsTemplateProviderBinding}.
 * <p>
 * Bindings supply provider-specific template ids / sign names after country
 * routing has determined the eligible provider accounts.
 */
public interface SmsTemplateProviderBindingService extends EntityService<SmsTemplateProviderBinding, Long> {

    /**
     * Find all enabled bindings for the given template, ordered by
     * {@code priority} ascending (lower = preferred).
     *
     * @param templateId the SMS template ID
     * @return ordered list of enabled bindings, or empty list if none configured
     */
    List<SmsTemplateProviderBinding> findByTemplateId(Long templateId);
}

package io.softa.starter.message.sms.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;
import io.softa.starter.message.sms.service.SmsTemplateProviderBindingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for SMS template-provider binding CRUD.
 * <p>
 * Manages the association between SMS templates and provider configs,
 * including failover ordering and provider-specific overrides.
 */
@Tag(name = "SmsTemplateProviderBinding")
@RestController
@RequestMapping("/SmsTemplateProviderBinding")
public class SmsTemplateProviderBindingController
        extends EntityController<SmsTemplateProviderBindingService, SmsTemplateProviderBinding, Long> {
}


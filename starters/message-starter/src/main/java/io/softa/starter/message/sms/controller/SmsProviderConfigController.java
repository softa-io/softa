package io.softa.starter.message.sms.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.service.SmsProviderConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for SMS provider configuration CRUD.
 */
@Tag(name = "SmsProviderConfig")
@RestController
@RequestMapping("/SmsProviderConfig")
public class SmsProviderConfigController
        extends EntityController<SmsProviderConfigService, SmsProviderConfig, Long> {
}

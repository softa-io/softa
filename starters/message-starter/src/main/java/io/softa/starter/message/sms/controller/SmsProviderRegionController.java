package io.softa.starter.message.sms.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.sms.entity.SmsProviderRegion;
import io.softa.starter.message.sms.service.SmsProviderRegionService;

/**
 * REST controller for SMS provider country-routing CRUD.
 */
@Tag(name = "SmsProviderRegion")
@RestController
@RequestMapping("/SmsProviderRegion")
public class SmsProviderRegionController
        extends EntityController<SmsProviderRegionService, SmsProviderRegion, Long> {
}

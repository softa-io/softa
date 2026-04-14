package io.softa.starter.message.sms.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for SMS template CRUD.
 */
@Tag(name = "SmsTemplate")
@RestController
@RequestMapping("/SmsTemplate")
public class SmsTemplateController
        extends EntityController<SmsTemplateService, SmsTemplate, Long> {
}

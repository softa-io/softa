package io.softa.starter.message.mail.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.service.MailTemplateService;

/**
 * REST controller for email template management.
 * <p>
 * Provides standard CRUD and search endpoints inherited from {@link EntityController}.
 * Templates support platform/tenant-level scoping.
 */
@Tag(name = "MailTemplate")
@RestController
@RequestMapping("/MailTemplate")
public class MailTemplateController
        extends EntityController<MailTemplateService, MailTemplate, Long> {
}

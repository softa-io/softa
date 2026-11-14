package io.softa.starter.message.mail.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.service.MailTemplateService;

/**
 * REST controller for a tenant's own email templates — standard CRUD and search from the generic
 * model routes, narrowed to the tenant by the ORM. The write rules (a pinned send server must be the
 * tenant's own, a platform code is refused) hold on every write path, so they live in
 * {@code MailTemplateWriteValidator}, not here. Preview and variable listing are under
 * {@code /api/mail/templates}.
 */
@Tag(name = "MailTemplate")
@RestController
@RequestMapping("/MailTemplate")
public class MailTemplateController extends EntityController<MailTemplateService, MailTemplate, Long> {
}

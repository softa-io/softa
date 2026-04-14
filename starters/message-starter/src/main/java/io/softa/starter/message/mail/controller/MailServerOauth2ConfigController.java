package io.softa.starter.message.mail.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.mail.entity.MailServerOauth2Config;
import io.softa.starter.message.mail.service.MailServerOauth2ConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for OAuth2 credentials linked to mail server configs.
 */
@Tag(name = "MailServerOauth2Config")
@RestController
@RequestMapping("/MailServerOauth2Config")
public class MailServerOauth2ConfigController
        extends EntityController<MailServerOauth2ConfigService, MailServerOauth2Config, Long> {
}

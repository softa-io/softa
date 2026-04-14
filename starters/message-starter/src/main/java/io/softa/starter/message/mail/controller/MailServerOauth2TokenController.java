package io.softa.starter.message.mail.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.starter.message.mail.entity.MailServerOauth2Token;
import io.softa.starter.message.mail.service.MailServerOauth2TokenService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for OAuth2 token storage per mail account.
 * <p>
 * Token exchange and refresh are handled by the application layer;
 * this controller exposes CRUD for managing stored tokens.
 */
@Tag(name = "MailServerOauth2Token")
@RestController
@RequestMapping("/MailServerOauth2Token")
public class MailServerOauth2TokenController
        extends EntityController<MailServerOauth2TokenService, MailServerOauth2Token, Long> {
}

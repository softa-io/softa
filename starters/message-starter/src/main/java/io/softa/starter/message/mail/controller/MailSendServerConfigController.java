package io.softa.starter.message.mail.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.service.MailSendServerConfigService;

/**
 * A tenant's own outgoing mail servers (SMTP / SMTPS): multi-tenant, managed from the tenant's Message menu.
 * The shared write endpoints are in {@link AbstractMailServerController}.
 */
@Tag(name = "MailSendServerConfig")
@RestController
@RequestMapping("/MailSendServerConfig")
public class MailSendServerConfigController extends AbstractMailServerController<MailSendServerConfigService, MailSendServerConfig> {

    @Override
    protected String modelName() {
        return "MailSendServerConfig";
    }
}

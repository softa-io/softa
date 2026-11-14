package io.softa.starter.message.mail.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;

/**
 * A tenant's own incoming mail servers (IMAP / IMAPS / POP3 / POP3S): multi-tenant, managed from the tenant's Message menu.
 * The shared write endpoints are in {@link AbstractMailServerController}.
 */
@Tag(name = "MailReceiveServerConfig")
@RestController
@RequestMapping("/MailReceiveServerConfig")
public class MailReceiveServerConfigController extends AbstractMailServerController<MailReceiveServerConfigService, MailReceiveServerConfig> {

    @Override
    protected String modelName() {
        return "MailReceiveServerConfig";
    }
}

package io.softa.starter.message.mail.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.starter.message.mail.entity.PlatformMailSendServer;
import io.softa.starter.message.mail.service.PlatformMailSendServerService;

/**
 * The platform's own outgoing mail servers (SMTP / SMTPS), managed from platform menus only.
 * The shared write endpoints are in {@link AbstractMailServerController}.
 */
@Tag(name = "PlatformMailSendServer")
@RestController
@RequestMapping("/PlatformMailSendServer")
public class PlatformMailSendServerController extends AbstractMailServerController<PlatformMailSendServerService, PlatformMailSendServer> {

    @Override
    protected String modelName() {
        return "PlatformMailSendServer";
    }
}

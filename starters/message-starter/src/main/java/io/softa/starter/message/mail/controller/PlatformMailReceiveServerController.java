package io.softa.starter.message.mail.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.starter.message.mail.entity.PlatformMailReceiveServer;
import io.softa.starter.message.mail.service.PlatformMailReceiveServerService;

/**
 * The platform's own incoming mail servers (IMAP / IMAPS / POP3 / POP3S), managed from platform menus only.
 * The shared write endpoints are in {@link AbstractMailServerController}.
 */
@Tag(name = "PlatformMailReceiveServer")
@RestController
@RequestMapping("/PlatformMailReceiveServer")
public class PlatformMailReceiveServerController extends AbstractMailServerController<PlatformMailReceiveServerService, PlatformMailReceiveServer> {

    @Override
    protected String modelName() {
        return "PlatformMailReceiveServer";
    }
}

package io.softa.starter.message.mail.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for outgoing mail server configuration (SMTP/SMTPS).
 */
@Tag(name = "MailSendServerConfig")
@RestController
@RequestMapping("/MailSendServerConfig")
public class MailSendServerConfigController
        extends EntityController<MailSendServerConfigService, MailSendServerConfig, Long> {

    @Operation(summary = "Test SMTP connectivity",
            description = "Verify that the system can connect to and authenticate with the SMTP server.")
    @PostMapping("/testConnectivity")
    public ApiResponse<ConnectivityTestResultDTO> testConnectivity(@RequestParam Long id) {
        return ApiResponse.success(service.testConnectivity(id));
    }
}

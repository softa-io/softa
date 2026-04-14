package io.softa.starter.message.mail.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for incoming mail server configuration (IMAP/IMAPS/POP3/POP3S).
 */
@Tag(name = "MailReceiveServerConfig")
@RestController
@RequestMapping("/MailReceiveServerConfig")
public class MailReceiveServerConfigController
        extends EntityController<MailReceiveServerConfigService, MailReceiveServerConfig, Long> {

    @Operation(summary = "Test IMAP/POP3 connectivity",
            description = "Verify that the system can connect to and authenticate with the receiving mail server.")
    @PostMapping("/testConnectivity")
    public ApiResponse<ConnectivityTestResultDTO> testConnectivity(@RequestParam Long id) {
        return ApiResponse.success(service.testConnectivity(id));
    }
}

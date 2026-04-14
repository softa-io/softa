package io.softa.starter.message.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Result of a mail server connectivity test.
 */
@Data
@Schema(name = "ConnectivityTestResultDTO")
public class ConnectivityTestResultDTO {

    @Schema(description = "Whether the connection and authentication succeeded")
    private Boolean success;

    @Schema(description = "Round-trip time in milliseconds")
    private Long latencyMs;

    @Schema(description = "Server greeting or capability response (SMTP 220 / IMAP * OK)")
    private String serverGreeting;

    @Schema(description = "Error detail when success is false")
    private String errorMessage;
}

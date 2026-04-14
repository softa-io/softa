package io.softa.starter.message.mail.dto;

import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Response DTO for querying the send status of a mail record.
 */
@Data
@Schema(name = "MailSendStatusDTO")
public class MailSendStatusDTO {

    public static MailSendStatusDTO from(MailSendRecord record) {
        MailSendStatusDTO dto = new MailSendStatusDTO();
        dto.setId(record.getId());
        dto.setSubject(record.getSubject());
        dto.setToAddresses(record.getToAddresses());
        dto.setStatus(record.getStatus());
        dto.setRetryCount(record.getRetryCount());
        dto.setErrorMessage(record.getErrorMessage());
        dto.setSentAt(record.getSentAt());
        dto.setReadReceiptReceived(record.getReadReceiptReceived());
        dto.setReadReceiptReceivedAt(record.getReadReceiptReceivedAt());
        dto.setBounced(record.getBounced());
        dto.setBounceCode(record.getBounceCode());
        return dto;
    }

    @Schema(description = "Send record ID")
    private Long id;

    @Schema(description = "Email subject")
    private String subject;

    @Schema(description = "To addresses (JSON array)")
    private String toAddresses;

    @Schema(description = "Current send status")
    private MailSendStatus status;

    @Schema(description = "Number of retry attempts")
    private Integer retryCount;

    @Schema(description = "Error message if failed")
    private String errorMessage;

    @Schema(description = "Timestamp when successfully sent")
    private LocalDateTime sentAt;

    @Schema(description = "Whether a read receipt has been received")
    private Boolean readReceiptReceived;

    @Schema(description = "Timestamp when the read receipt was received")
    private LocalDateTime readReceiptReceivedAt;

    @Schema(description = "Whether this email bounced")
    private Boolean bounced;

    @Schema(description = "Bounce code summary, e.g. '550 5.1.1'")
    private String bounceCode;
}

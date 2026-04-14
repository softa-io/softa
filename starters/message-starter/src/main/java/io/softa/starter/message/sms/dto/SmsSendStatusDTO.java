package io.softa.starter.message.sms.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;

/**
 * Response DTO for querying SMS send status.
 */
@Data
@Schema(name = "SmsSendStatusDTO")
public class SmsSendStatusDTO {

    public static SmsSendStatusDTO from(SmsSendRecord record) {
        SmsSendStatusDTO dto = new SmsSendStatusDTO();
        dto.setId(record.getId());
        dto.setProviderType(record.getProviderType());
        dto.setPhoneNumber(record.getPhoneNumber());
        dto.setContentPreview(record.getContentPreview());
        dto.setStatus(record.getStatus());
        dto.setRetryCount(record.getRetryCount());
        dto.setErrorMessage(record.getErrorMessage());
        dto.setErrorCode(record.getErrorCode());
        dto.setSentAt(record.getSentAt());
        dto.setProviderMessageId(record.getProviderMessageId());
        dto.setDeliveryStatus(record.getDeliveryStatus());
        dto.setDeliveryStatusUpdatedAt(record.getDeliveryStatusUpdatedAt());
        return dto;
    }

    @Schema(description = "Send record ID")
    private Long id;

    @Schema(description = "Provider type used for this send")
    private SmsProvider providerType;

    @Schema(description = "Recipient phone number")
    private String phoneNumber;

    @Schema(description = "Content preview")
    private String contentPreview;

    @Schema(description = "Send status")
    private SmsSendStatus status;

    @Schema(description = "Number of send attempts")
    private Integer retryCount;

    @Schema(description = "Error message on failure")
    private String errorMessage;

    @Schema(description = "Provider-specific error code on failure")
    private String errorCode;

    @Schema(description = "Timestamp when sent")
    private LocalDateTime sentAt;

    @Schema(description = "External message ID from the provider")
    private String providerMessageId;

    @Schema(description = "Delivery status reported by the provider")
    private SmsDeliveryStatus deliveryStatus;

    @Schema(description = "Last delivery status update time")
    private LocalDateTime deliveryStatusUpdatedAt;
}

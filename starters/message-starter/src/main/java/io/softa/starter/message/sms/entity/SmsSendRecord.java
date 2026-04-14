package io.softa.starter.message.sms.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;

/**
 * SMS send record (audit log).
 * <p>
 * Each record corresponds to a single phone number. For batch sends,
 * one record per recipient is created.
 * Written automatically by SmsSendService; not created manually via API.
 */
@Data
@Schema(name = "SmsSendRecord")
@EqualsAndHashCode(callSuper = true)
public class SmsSendRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "SMS provider config used to send this message")
    private Long providerConfigId;

    @Schema(description = "Provider type used for this send (Twilio, Infobip, etc.)")
    private SmsProvider providerType;

    @Schema(description = "Recipient phone number")
    private String phoneNumber;

    @Schema(description = "Template code if sent via template")
    private String templateCode;

    @Schema(description = "Rendered SMS content")
    private String content;

    @Schema(description = "First 200 characters of content for list display")
    private String contentPreview;

    @Schema(description = "Send status")
    private SmsSendStatus status;

    @Schema(description = "Number of send attempts")
    private Integer retryCount;

    @Schema(description = "Error message on failure")
    private String errorMessage;

    @Schema(description = "Provider-specific error code on failure (e.g. Twilio 21211, Aliyun isv.BUSINESS_LIMIT_CONTROL)")
    private String errorCode;

    @Schema(description = "External message ID from the SMS provider")
    private String providerMessageId;

    @Schema(description = "Timestamp when the message was accepted by the provider")
    private LocalDateTime sentAt;

    @Schema(description = "Delivery status reported by the provider")
    private SmsDeliveryStatus deliveryStatus;

    @Schema(description = "Last delivery status update time")
    private LocalDateTime deliveryStatusUpdatedAt;
}

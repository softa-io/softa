package io.softa.starter.message.sms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Result returned by an {@link io.softa.starter.message.sms.support.adapter.SmsProviderAdapter}
 * after attempting to send an SMS.
 */
@Data
@Schema(name = "SmsSendResult")
public class SmsSendResult {

    @Schema(description = "Whether the send was accepted by the provider")
    private boolean success;

    @Schema(description = "External message ID assigned by the provider")
    private String providerMessageId;

    @Schema(description = "Provider-specific error code on failure")
    private String errorCode;

    @Schema(description = "Error message on failure")
    private String errorMessage;

    public static SmsSendResult success(String providerMessageId) {
        SmsSendResult r = new SmsSendResult();
        r.success = true;
        r.providerMessageId = providerMessageId;
        return r;
    }

    public static SmsSendResult failure(String errorCode, String errorMessage) {
        SmsSendResult r = new SmsSendResult();
        r.success = false;
        r.errorCode = errorCode;
        r.errorMessage = errorMessage;
        return r;
    }
}

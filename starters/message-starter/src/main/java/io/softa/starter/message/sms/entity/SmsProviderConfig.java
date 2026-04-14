package io.softa.starter.message.sms.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * SMS provider configuration.
 * <p>
 * Stores credentials and settings for a third-party SMS provider (Twilio, Infobip, etc.).
 * Uses generic credential fields to avoid provider-specific columns — each adapter
 * interprets these fields according to its provider's requirements.
 * <p>
 * tenant_id = 0  — platform-level, shared across all tenants.
 * tenant_id > 0  — tenant-level, ORM auto-fills and isolates.
 */
@Data
@Schema(name = "SmsProviderConfig")
@EqualsAndHashCode(callSuper = true)
public class SmsProviderConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Config name")
    private String name;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Provider type: Twilio / Infobip / Aliyun / Tencent / Custom")
    private SmsProvider providerType;

    @Schema(description = "Primary credential (Twilio=accountSid, Infobip=apiKey, Aliyun=accessKeyId)")
    private String apiKey;

    @Schema(description = "Secondary credential (Twilio=authToken, Infobip=apiSecret, Aliyun=accessKeySecret)")
    private String apiSecret;

    @Schema(description = "Provider API base URL (null = adapter default)")
    private String apiEndpoint;

    @Schema(description = "Extra provider identifier (e.g. Twilio Account SID when using API keys)")
    private String accountId;

    @Schema(description = "Provider-specific JSON config (e.g. regionId, appId)")
    private String extConfig;

    @Schema(description = "Sender phone number (E.164 format)")
    private String senderNumber;

    @Schema(description = "Alphanumeric sender ID (alternative to phone number)")
    private String senderId;

    @Schema(description = "Max SMS per minute")
    private Integer rateLimitPerMinute;

    @Schema(description = "Max SMS per day (null = unlimited)")
    private Integer dailySendLimit;

    @Schema(description = "Whether this is the default config for this tenant")
    private Boolean isDefault;

    @Schema(description = "Whether this config is active")
    private Boolean isEnabled;

    @Schema(description = "Sort order for multiple configs")
    private Integer sortOrder;

    @Schema(description = "Max retry attempts on send failure (0 = no retry)")
    private Integer maxRetryCount;

    @Schema(description = "Delay in seconds between retry attempts (default 60)")
    private Integer retryIntervalSeconds;
}

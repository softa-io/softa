package io.softa.starter.message.sms.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.sms.enums.SmsProvider;

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
@Model(label = "SMS Provider Config", idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true)
@Index(indexName = "idx_sms_provider_cfg_default", fields = {"tenantId", "isDefault"})
@EqualsAndHashCode(callSuper = true)
public class SmsProviderConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "0 = platform-level (shared across tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(label = "Config Name", required = true, length = 100)
    private String name;

    @Field(length = 500)
    private String description;

    @Field(required = true,
            description = "Provider type: Twilio / Infobip / Aliyun / Tencent / Custom")
    private SmsProvider providerType;

    @Field(label = "API Key", length = 255, copyable = false,
            description = "Primary credential (Twilio=accountSid, Infobip=apiKey, Aliyun=accessKeyId)")
    private String apiKey;

    @Field(label = "API Secret", copyable = false,
            description = "Secondary credential (Twilio=authToken, Infobip=apiSecret, Aliyun=accessKeySecret) — stored encrypted, mark MetaField.encrypted=true")
    private String apiSecret;

    @Field(label = "API Endpoint", length = 500,
            description = "Provider API base URL (null = adapter default)")
    private String apiEndpoint;

    @Field(label = "Account ID", length = 255,
            description = "Extra provider identifier (e.g. Twilio Account SID when using API keys)")
    private String accountId;

    @Field(description = "Provider-specific JSON config (e.g. regionId, appId)")
    private String extConfig;

    @Field(length = 50,
            description = "Sender phone number (E.164 format)")
    private String senderNumber;

    @Field(label = "Sender ID", length = 50,
            description = "Alphanumeric sender ID (alternative to phone number)")
    private String senderId;

    @Field(description = "Max SMS per minute")
    private Integer rateLimitPerMinute;

    @Field(description = "Max SMS per day (null = unlimited)")
    private Integer dailySendLimit;

    @Field(description = "Marks this provider as a catchall — included in the SMS dispatcher's "
                    + "second tier when no enabled sms_provider_region route matches the recipient's country. "
                    + "Multiple providers may be defaults; they're ordered by priority before one provider is "
                    + "selected and persisted. Orthogonal to precise region routing — a provider can be both "
                    + "a TW route (via sms_provider_region) and a catchall (via this flag).")
    private Boolean isDefault;

    @Field(description = "Whether this config is active")
    private Boolean isEnabled;

    @Field(description = "Lower = higher priority. Used as selection ordering among "
                    + "isDefault=true providers in the catchall dispatch tier, and as the list-display "
                    + "order in admin UIs. Defaults to 100 so new configs sort after explicitly-prioritised ones.")
    private Integer priority;
}

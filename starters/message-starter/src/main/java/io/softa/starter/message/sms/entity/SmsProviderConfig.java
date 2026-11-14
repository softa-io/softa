package io.softa.starter.message.sms.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.enums.MaskingType;
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
 * Platform-owned: SMS is a platform service, so providers, country routing,
 * templates and template bindings exist once, for every tenant. The table is
 * not multi-tenant and has no tenant column; tenants never see or configure it.
 */
@Data
@Model(label = "SMS Provider Config", idStrategy = IdStrategy.DISTRIBUTED_LONG,
        activeControl = true,
        defaultOrder = {"priority", "name"})
@Index(indexName = "idx_sms_provider_cfg_default", fields = {"isDefault"})
@EqualsAndHashCode(callSuper = true)
public class SmsProviderConfig extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Config Name", required = true, length = 100)
    private String name;

    @Field(length = 500)
    private String description;

    @Field(required = true,
            description = "Provider type: Twilio / Infobip / Aliyun / Tencent / Custom")
    private SmsProvider providerType;

    @Field(label = "API Key", length = 512, encrypted = true, copyable = false, unsearchable = true,
            maskingType = MaskingType.ALL,
            description = "Primary credential (Twilio=accountSid, Infobip=apiKey, Aliyun=accessKeyId) — "
                    + "AES-encrypted at rest, masked in API reads. Omit the field on update to keep the "
                    + "stored value; a payload containing the mask symbol is rejected.")
    private String apiKey;

    @Field(label = "API Secret", length = 512, encrypted = true, copyable = false, unsearchable = true,
            maskingType = MaskingType.ALL,
            description = "Secondary credential (Twilio=authToken, Infobip=apiSecret, Aliyun=accessKeySecret) — "
                    + "AES-encrypted at rest, masked in API reads. Omit the field on update to keep the "
                    + "stored value; a payload containing the mask symbol is rejected.")
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

    @Field(description = "Marks this provider as a catchall for second-tier dispatch, used when "
                    + "no enabled sms_provider_region route matches the recipient's country. "
                    + "Multiple defaults are ordered by priority before one is selected and "
                    + "persisted. Orthogonal to region routing — a provider can be both a region "
                    + "route and a catchall.")
    private Boolean isDefault;

    /**
     * Framework active control: reads are auto-filtered to {@code active = true},
     * so a disabled row is retired from every list and every resolution without
     * being deleted (rows referenced by send records stay intact). Filter on
     * {@code active} explicitly to see disabled rows.
     */
    @Field(renamedFrom = "isEnabled")
    private Boolean active;

    @Field(description = "Lower = higher priority. Used as selection ordering among "
                    + "isDefault=true providers in the catchall dispatch tier, and as the list-display "
                    + "order in admin UIs. Defaults to 100 so new configs sort after explicitly-prioritised ones.")
    private Integer priority;
}

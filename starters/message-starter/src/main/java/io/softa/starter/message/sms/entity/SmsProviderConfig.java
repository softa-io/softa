package io.softa.starter.message.sms.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
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
 * <p>
 * A config sends to no country by itself: which countries it serves, and in which
 * order, is said only by its {@link SmsProviderRegion} rows.
 */
@Data
@Model(label = "SMS Provider Config", idStrategy = IdStrategy.DISTRIBUTED_LONG,
        activeControl = true,
        defaultOrder = {"name"})
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
            description = "Secondary credential (Twilio=authToken, Aliyun=accessKeySecret; unused by Infobip) — "
                    + "AES-encrypted at rest, masked in API reads. Omit the field on update to keep the "
                    + "stored value; a payload containing the mask symbol is rejected.")
    private String apiSecret;

    @Field(label = "API Endpoint", length = 500,
            description = "Provider API base URL (null = adapter default; required for Infobip, "
                    + "the account's own base URL, e.g. https://xxxxx.api.infobip.com)")
    private String apiEndpoint;

    @Field(label = "Account ID", length = 255,
            description = "Extra provider identifier (e.g. Twilio Account SID when using API keys)")
    private String accountId;

    @Field(description = "Provider-specific JSON config (e.g. regionId, appId)")
    private String extConfig;

    @Field(length = 50,
            description = "Numeric sender: a long number in E.164 (e.g. +6591234567) or a short code "
                    + "(e.g. a New Zealand 4–6 digit code), entered as the provider issued it")
    private String senderNumber;

    @Field(label = "Sender ID", length = 50,
            description = "Alphanumeric sender ID such as a brand name (alternative to a sender number); "
                    + "some countries require it to be registered first (Singapore: SSIR)")
    private String senderId;

    @Field(description = "Max SMS per minute")
    private Integer rateLimitPerMinute;

    @Field(description = "Max SMS per day (null = unlimited)")
    private Integer dailySendLimit;

    /**
     * Framework active control: reads are auto-filtered to {@code active = true},
     * so a disabled row is retired from every list and every resolution without
     * being deleted (rows referenced by send records stay intact). Filter on
     * {@code active} explicitly to see disabled rows.
     */
    @Field(renamedFrom = "isEnabled")
    private Boolean active;

    /** The countries this provider serves — edited on the provider's page, queried by country on its own. */
    @Field(label = "Country Routing", fieldType = FieldType.ONE_TO_MANY, relatedField = "providerConfigId")
    private List<SmsProviderRegion> regions;
}

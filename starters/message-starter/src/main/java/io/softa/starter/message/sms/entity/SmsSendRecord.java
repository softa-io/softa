package io.softa.starter.message.sms.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;

/**
 * SMS send record (audit log).
 * <p>
 * Each record corresponds to a single phone number. For batch sends,
 * one record per recipient is created.
 * Written automatically by MessageService; not created manually via API.
 * <p>
 * Acceptance stores the request as the caller made it — the number, any explicit
 * content, the template it named and its variables, the tier. The first delivery
 * attempt renders the template, routes the number to a provider and charges the
 * monthly quota, and writes the result back here; retries replay what that attempt
 * prepared. A request that cannot be prepared (no provider for the country, no
 * template, quota used up) still leaves its record, FAILED with the reason.
 */
@Data
@Model(label = "SMS Send Record", idStrategy = IdStrategy.DISTRIBUTED_LONG, versionLock = true, copyable = false, multiTenant = true)
@Index(indexName = "idx_sms_send_tenant_status", fields = {"tenantId", "status"})
@Index(indexName = "idx_sms_send_sent_at", fields = {"sentAt"})
@Index(indexName = "idx_sms_send_status_updated", fields = {"status", "updatedTime"})
@Index(indexName = "idx_sms_send_status_retry", fields = {"status", "nextRetryAt"})
@Index(indexName = "idx_provider_msg_id", fields = {"providerMessageId"})
@EqualsAndHashCode(callSuper = true)
public class SmsSendRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID",
            description = "-1 = platform-tier (invisible to tenants); >0 = tenant-level. "
                    + "Auto-stamped by the ORM on writes when multi-tenancy is enabled.")
    private Long tenantId;

    @Field(description = "Tier policy of the send: true = PLATFORM scope (the platform tier renders the "
            + "template and is charged), false = TENANT scope.")
    private Boolean platformScope;

    @Field(label = "Trace ID", description = "Trace id of the request that submitted the send; "
            + "every delivery attempt runs and logs under it")
    private String traceId;

    @Field(label = "Provider Config ID", description = "SMS provider config that carries this message: the "
            + "caller's choice, else the one the first delivery attempt routed the number to")
    private Long providerConfigId;

    @Field(description = "Provider type used for this send (Twilio, Infobip, etc.); "
            + "empty until the first delivery attempt routes the number")
    private SmsProvider providerType;

    @Field(description = "Recipient phone number", length = 50)
    private String phoneNumber;

    @Field(description = "Template code if sent via template", length = 100)
    private String templateCode;

    @Field(fieldType = FieldType.JSON, description = "Template placeholder values the caller passed")
    private JsonNode templateVariables;

    @Field(length = 2000, description = "SMS content: the caller's text, or the template rendered "
            + "by the first delivery attempt")
    private String content;

    @Field(description = "SMS signature (sign name) actually used at send time. "
            + "Persisted for retry fidelity — if SmsTemplateProviderBinding.signName is "
            + "edited between first send and retry, retries still use the original value.")
    private String signName;

    @Field(label = "External Template ID", length = 100, description = "Provider-side pre-registered template ID actually used at send "
            + "time (e.g. Aliyun SMS_12345678, Tencent 1234567). Persisted for retry "
            + "fidelity — see signName for the same reasoning.")
    private String externalTemplateId;

    @Field(required = true, description = "Send status")
    private SmsSendStatus status;

    @Field(description = "Number of send attempts")
    private Integer retryCount;

    @Field(required = true, description = "Optimistic-lock version. Bumped on every state transition.")
    private Long version;

    @Field(description = "Earliest time at which the next retry should be attempted")
    private LocalDateTime nextRetryAt;

    @Field(length = 1000, description = "Error message on failure")
    private String errorMessage;

    @Field(length = 100, description = "Provider-specific error code on failure (e.g. Twilio 21211, Aliyun isv.BUSINESS_LIMIT_CONTROL)")
    private String errorCode;

    @Field(label = "Provider Message ID", description = "External message ID from the SMS provider", length = 255)
    private String providerMessageId;

    @Field(description = "Timestamp when the message was accepted by the provider")
    private LocalDateTime sentAt;

    @Field(description = "When the latest delivery attempt started: the delivery loop claimed the record "
            + "(status → Sending).")
    private LocalDateTime attemptStartedAt;

    @Field(label = "Send Duration (ms)", description = "How long the provider API call of the successful "
            + "attempt took, in milliseconds. Null until the record is sent.")
    private Integer sendDurationMs;

    @Field(description = "Delivery status reported by the provider")
    private SmsDeliveryStatus deliveryStatus;

    @Field(description = "Last delivery status update time")
    private LocalDateTime deliveryStatusUpdatedAt;
}

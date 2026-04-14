package io.softa.starter.message.sms.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * Binds an {@link SmsTemplate} to a specific {@link SmsProviderConfig} with
 * provider-specific overrides (external template ID, sign name) and an ordering
 * for cross-channel failover.
 * <p>
 * When a template has one or more enabled bindings, the SMS send service will
 * iterate them in {@code sortOrder} ascending order, attempting each provider
 * until one succeeds. If no bindings are configured, the default dispatch logic
 * is used (tenant default → platform default).
 * <p>
 * tenant_id = 0  — platform-level binding, shared across all tenants.
 * tenant_id > 0  — tenant-level binding, ORM auto-fills and isolates.
 */
@Data
@Schema(name = "SmsTemplateProviderBinding")
@EqualsAndHashCode(callSuper = true)
public class SmsTemplateProviderBinding extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "FK → sms_template.id")
    private Long templateId;

    @Schema(description = "FK → sms_provider_config.id")
    private Long providerConfigId;

    @Schema(description = "Pre-registered template ID for providers that require it (e.g. Aliyun SMS_12345678)")
    private String externalTemplateId;

    @Schema(description = "SMS signature for this provider (e.g. Chinese providers require a sign name)")
    private String signName;

    @Schema(description = "Sort order for failover priority (lower = higher priority, tried first)")
    private Integer sortOrder;

    @Schema(description = "Whether this binding is active")
    private Boolean isEnabled;
}


package io.softa.starter.message.sms.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.referencedata.entity.CountryRegion;

/**
 * Binds an {@link SmsTemplate} to a specific {@link SmsProviderConfig} with
 * provider-specific overrides (external template ID, sign name) and an ordering
 * for template-aware provider selection.
 * <p>
 * Bindings are resolved after country routing: the recipient's phone number
 * first yields eligible providers, then this table supplies the
 * provider-specific external template id / sign name for the selected provider.
 * {@link #regionCode} is optional; blank means a generic binding for all
 * regions served by that provider, while a concrete ISO country code overrides
 * the generic binding for that region.
 * <p>
 * Platform-owned, like the templates and providers it joins: the table is not
 * multi-tenant and has no tenant column.
 */
@Data
@Model(label = "SMS Template Provider Binding", idStrategy = IdStrategy.DISTRIBUTED_LONG,
        activeControl = true,
        defaultOrder = {"templateId", "priority"})
@Index(indexName = "idx_template_priority", fields = {"templateId", "priority"})
@Index(indexName = "uk_sms_tmpl_provider_region", fields = {"templateId", "providerConfigId", "regionCode"}, unique = true)
@Index(indexName = "idx_template_region", fields = {"templateId", "regionCode"})
@EqualsAndHashCode(callSuper = true)
public class SmsTemplateProviderBinding extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Template ID", required = true, description = "FK → sms_template.id")
    private Long templateId;

    @Field(label = "Provider Config ID", required = true, description = "FK → sms_provider_config.id")
    private Long providerConfigId;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = CountryRegion.class,
            description = "Optional ISO 3166-1 alpha-2 region override. Blank = generic binding for this provider.")
    private String regionCode;

    @Field(label = "External Template ID", length = 100, description = "Pre-registered template ID for providers that require it (e.g. Aliyun SMS_12345678)")
    private String externalTemplateId;

    @Field(length = 50, description = "SMS signature for this provider (e.g. Chinese providers require a sign name)")
    private String signName;

    @Field(description = "Template-aware provider selection priority (lower = preferred)")
    private Integer priority;

    /**
     * Framework active control: reads are auto-filtered to {@code active = true},
     * so a disabled row is retired from every list and every resolution without
     * being deleted (rows referenced by send records stay intact). Filter on
     * {@code active} explicitly to see disabled rows.
     */
    @Field(renamedFrom = "isEnabled")
    private Boolean active;
}

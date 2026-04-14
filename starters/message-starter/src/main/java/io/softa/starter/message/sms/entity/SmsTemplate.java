package io.softa.starter.message.sms.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.Language;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * SMS template.
 * <p>
 * Templates are identified by {@code code} and support per-language variants.
 * Resolution priority (from highest to lowest):
 * <ol>
 *   <li>Tenant template matching current language</li>
 *   <li>Tenant template with language = "default"</li>
 *   <li>Platform template (tenant_id = 0) matching current language</li>
 *   <li>Platform template with language = "default"</li>
 * </ol>
 * <p>
 * Content supports {@code {{ variable }}} placeholders rendered by
 * {@link io.softa.framework.base.placeholder.PlaceholderUtils}.
 * <p>
 * tenant_id = 0  — platform-level, shared across all tenants.
 * tenant_id > 0  — tenant-level, ORM auto-fills and isolates.
 */
@Data
@Schema(name = "SmsTemplate")
@EqualsAndHashCode(callSuper = true)
public class SmsTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Unique template code used for programmatic lookup, e.g. VERIFY_CODE")
    private String code;

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Language tag, e.g. en-US, zh-CN. Use 'default' as the language-agnostic fallback")
    private Language language;

    @Schema(description = "SMS body template with {{ variable }} placeholders")
    private String content;

    @Schema(description = "Whether this template is active")
    private Boolean isEnabled;
}

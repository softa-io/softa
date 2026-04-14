package io.softa.starter.message.mail.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.Language;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * Email template.
 * <p>
 * Templates are identified by {@code code} and support per-language variants.
 * Resolution priority (from highest to lowest):
 * <ol>
 *   <li>Tenant template matching current language</li>
 *   <li>Tenant template with language = "default"</li>
 *   <li>Platform template (tenant_id = 0) matching current language</li>
 *   <li>Platform template with language = "default"</li>
 * </ol>
 * tenant_id = 0  — platform-level, shared across all tenants.
 * tenant_id > 0  — tenant-level, ORM auto-fills and isolates.
 */
@Data
@Schema(name = "MailTemplate")
@EqualsAndHashCode(callSuper = true)
public class MailTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Unique template code used for programmatic lookup, e.g. USER_WELCOME")
    private String code;

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Language tag, e.g. en-US, zh-CN. Use 'default' as the language-agnostic fallback")
    private Language language;

    @Schema(description = "Email subject template, supports {{ variable }} placeholders")
    private String subject;

    @Schema(description = "Email body template (HTML), supports {{ variable }} placeholders")
    private String body;

    @Schema(description = "Whether to also include a plain-text version alongside the HTML body. "
            + "When true, the plain text is automatically stripped from the rendered HTML.")
    private Boolean includePlainText;

    @Schema(description = "Whether this template is active")
    private Boolean isEnabled;

    @Schema(description = "Default email priority for this template (MailPriority enum name: High/Normal/Low). "
            + "When set, all emails sent via this template will use this priority unless overridden in SendMailDTO.")
    private String defaultPriority;
}

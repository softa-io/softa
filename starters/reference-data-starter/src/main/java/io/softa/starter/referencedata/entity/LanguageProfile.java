package io.softa.starter.referencedata.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.Language;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * Per-language formatting profile (date / time / decimal / thousand separators)
 * plus the runtime enable flag.
 *
 * <p>This is <em>not</em> a pure standards catalog — it mixes the language
 * identifier ({@link #language}, {@link #name}) with locale-flavoured formatting
 * conventions that tenants may legitimately want to override (e.g. a Brazilian
 * tenant displaying {@code 1.234,56} while another tenant on the same Portuguese
 * language prefers {@code 1,234.56}). Hence the table is tenant-scoped using
 * the platform-default + sparse-override pattern documented on
 * {@code SmsProviderRegion}:
 * <ul>
 *   <li>{@code tenant_id = 0} (or {@code NULL}) — platform default row, shared
 *       by all tenants that don't override.</li>
 *   <li>{@code tenant_id > 0} — per-tenant override of a specific language
 *       profile. Tenants only need to insert rows for the languages they
 *       actually want to deviate from the platform default.</li>
 * </ul>
 *
 * <p>{@code i18n} translation tables (e.g. {@code sys_field_trans},
 * {@code sys_option_item_trans}, {@code design_*_trans}) reference this row by
 * the {@link #language} string — concept FK, no relational constraint.
 *
 * <p>Note: {@link Language} lives in {@code framework/softa-base} and cannot
 * carry {@code @OptionSet} (would create base→orm cycle). It remains a
 * {@code ownership = PLATFORM_DEFAULT} option set seeded via DML; the scanner
 * emits a {@code sys_field} row referencing {@code optionSetCode = "Language"}
 * which resolves against that platform-default row.
 */
@Data
@Schema(name = "LanguageProfile")
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Language Profile",
        tableName = "language_profile",
        multiTenant = true,
        activeControl = true,
        businessKey = {"tenantId", "language"},
        description = "Per-language formatting profile, tenant-scoped"
)
public class LanguageProfile extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Field(label = "Tenant ID", length = 32,
            description = "Tenant ID — '0' or null for the platform-default row")
    private String tenantId;

    @Field(label = "Language Name", required = true, length = 64)
    private String name;

    @Field(label = "Language", required = true,
            description = "BCP-47 language tag; resolves to framework Language enum")
    private Language language;

    @Field(label = "Date Format", length = 32)
    private String dateFormat;

    @Field(label = "Time Format", length = 32)
    private String timeFormat;

    @Field(label = "Decimal Separator", length = 32)
    private String decimalSeparator;

    @Field(label = "Thousand Separator", length = 32)
    private String thousandSeparator;

    @Field(label = "Active")
    private Boolean active;
}

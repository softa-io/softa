package io.softa.starter.referencedata.entity;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * Platform-level currency master keyed by ISO 4217 alpha-3 code.
 * Read-only reference data — same rows serve all tenants. Seed loaded from
 * {@code data-system/Currency.AllCurrencies.json}.
 *
 * <p>Natural key is {@link #code} (ISO 4217 alpha-3). The
 * {@link #decimalPlaces} field is <b>critical</b> for monetary arithmetic
 * — JPY/KRW use 0 fraction digits, USD/EUR/CNY use 2, BHD/KWD use 3.
 * Mismatching these breaks currency rendering and rounding for that country.
 */
@Data
@Schema(name = "Currency")
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "Currency",
        tableName = "currency",
        businessKey = {"code"},
        description = "ISO 4217 currency master"
)
@Index(indexName = "uk_code", fields = {"code"}, unique = true)
public class Currency extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Field(label = "ISO 4217 alpha-3", required = true, length = 3,
            description = "ISO 4217 alpha-3 code (USD/CNY/EUR/...); natural key")
    private String code;

    @Field(label = "Numeric Code", required = true, length = 3,
            description = "ISO 4217 numeric, 3 digits with leading zero (840/156/048)")
    private String numericCode;

    @Field(label = "Name", required = true, length = 100,
            description = "English name, e.g. 'US Dollar'")
    private String name;

    @Field(label = "Symbol", required = true, length = 10,
            description = "Unicode display symbol ($ / ¥ / € / ₹ / £ / ...)")
    private String symbol;

    @Field(label = "Decimal Places", required = true,
            description = "ISO 4217 fraction digits — 0 for JPY/KRW, 2 for USD/EUR/CNY, "
                    + "3 for BHD/KWD/IQD. CRITICAL for monetary arithmetic")
    private Integer decimalPlaces;
}

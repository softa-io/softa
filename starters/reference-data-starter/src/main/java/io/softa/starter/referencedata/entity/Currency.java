package io.softa.starter.referencedata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Platform-level currency master keyed by ISO 4217 alpha-3 code.
 * Read-only reference data — same rows serve all tenants. Seed loaded from
 * {@code data-system/Currency.AllCurrencies.json}.
 *
 * <p>Code-as-id: the primary key {@link #id} <b>is</b> the ISO 4217 alpha-3 code
 * (EXTERNAL_ID), so references store the human/portable code and the picker stays id-native.
 * The {@link #decimalPlaces} field is <b>critical</b> for monetary arithmetic — JPY/KRW use 0
 * fraction digits, USD/EUR/CNY use 2, BHD/KWD use 3. Mismatching these breaks currency
 * rendering and rounding for that country.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.EXTERNAL_ID,
        businessKey = {"id"},
        description = "ISO 4217 currency master",
        searchName = {"id", "name"},
        defaultOrder = {"name"}
)
public class Currency extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID", length = 3,
            description = "ISO 4217 alpha-3 code (USD/CNY/EUR/...); primary key = the code")
    private String id;

    @Field(required = true, length = 3,
            description = "ISO 4217 numeric, 3 digits with leading zero (840/156/048)")
    private String numericCode;

    @Field(required = true, length = 100,
            description = "English name, e.g. 'US Dollar'")
    private String name;

    @Field(required = true, length = 10,
            description = "Unicode display symbol ($ / ¥ / € / ₹ / £ / ...)")
    private String symbol;

    @Field(required = true,
            description = "ISO 4217 fraction digits — 0 for JPY/KRW, 2 for USD/EUR/CNY, "
                    + "3 for BHD/KWD/IQD. CRITICAL for monetary arithmetic")
    private Integer decimalPlaces;
}

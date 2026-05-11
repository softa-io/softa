package io.softa.starter.referencedata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Trimmed projection of {@code CountryRegion} for phone-number / international
 * dial-code selector components. Excludes audit fields and continent/EEA flags
 * that the dial-code use case never needs.
 *
 * <p><b>dialCode is not unique across countries</b> — NANP territories
 * (US/CA/JM/...) all share '1' and RU/KZ share '7'. The frontend keeps one
 * row per country and disambiguates by name / flag.
 */
@Data
@Schema(name = "DialCodeItemDTO")
public class DialCodeItemDTO {

    @Schema(description = "ISO 3166-1 alpha-2 code, primary identifier (CN/US/...)")
    private String code;

    @Schema(description = "ISO 3166-1 standard English short name")
    private String name;

    @Schema(description = "ITU-T E.164 country dial code, digits only (no leading +)")
    private String dialCode;

    @Schema(description = "ISO 3166-1 alpha-3 code (CHN/USA/...). Some payment forms require alpha-3.")
    private String alpha3Code;
}

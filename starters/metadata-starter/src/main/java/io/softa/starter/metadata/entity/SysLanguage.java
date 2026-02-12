package io.softa.starter.metadata.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * SysLanguage Model
 */
@Data
@Schema(name = "SysLanguage")
@EqualsAndHashCode(callSuper = true)
public class SysLanguage extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Language Name")
    private String name;

    @Schema(description = "Language Code")
    private String code;

    @Schema(description = "Date Format")
    private String dateFormat;

    @Schema(description = "Time Format")
    private String timeFormat;

    @Schema(description = "Decimal Separator")
    private String decimalSeparator;

    @Schema(description = "Thousand Separator")
    private String thousandSeparator;

    @Schema(description = "Active")
    private Boolean active;
}
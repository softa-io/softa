package io.softa.framework.orm.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.enums.TenantLifecycle;
import io.softa.framework.orm.enums.TenantStatus;

/**
 * TenantInfo Model
 */
@Data
@Schema(name = "TenantInfo")
@EqualsAndHashCode(callSuper = true)
public class TenantInfo extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Code")
    private String code;

    @Schema(description = "Status")
    private TenantStatus status;

    @Schema(description = "Lifecycle Stage")
    private TenantLifecycle lifecycle;

    @Schema(description = "Activated Time")
    private LocalDateTime activatedTime;

    @Schema(description = "Suspended Time")
    private LocalDateTime suspendedTime;

    @Schema(description = "Closed Time")
    private LocalDateTime closedTime;

    @Schema(description = "Default Language")
    private Language defaultLanguage;

    @Schema(description = "Default Timezone")
    private Timezone defaultTimezone;

    @Schema(description = "Default Currency")
    private String defaultCurrency;

    @Schema(description = "Default Country")
    private Long defaultCountry;

    @Schema(description = "Data Region")
    private String dataRegion;

    @Schema(description = "Plan ID")
    private Long planId;

    @Schema(description = "Subscription ID")
    private Long subscriptionId;

    @Schema(description = "Deleted")
    private Boolean deleted;
}

package io.softa.starter.metadata.sequence.entity;

import java.io.Serial;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.metadata.sequence.enums.ResetCadence;
import io.softa.starter.metadata.sequence.enums.SequenceMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Sequence generator configuration and counter.
 * Each row holds a single (tenant, code) counter plus its rendering template
 * and reset / mode policy. Allocation goes through
 * {@code io.softa.framework.orm.sequence.SequenceService} (port in softa-orm,
 * implementation in this starter).
 *
 * <p>v1 hard rules (also enforced at config save time):
 * <ul>
 *   <li>{@code code} matches {@code "<ModelName>.<fieldName>"} for
 *       fields participating in auto-fill (see SequenceProcessor).</li>
 *   <li>{@code incrementStep == 1}.</li>
 *   <li>Admin cannot change {@code code}; cannot create or delete rows via
 *       the API. Use the framework's {@code loadPreTenantData} on JSON
 *       files for tenant bootstrap.</li>
 * </ul>
 *
 * <p>There is no {@code status} column: a row's existence equals "active".
 * Emergency disable is a DBA-only operation (DELETE the row, optionally
 * preserving {@code currentValue} elsewhere if a later restoration is
 * needed).
 */
@Data
@Schema(name = "SysSequence")
@EqualsAndHashCode(callSuper = true)
public class SysSequence extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Sequence code, e.g. \"Employee.code\"")
    private String code;

    @Schema(description = "Format template, e.g. EMP-{yyyy}-{seq:5}")
    private String template;

    @Schema(description = "First number after each reset (default 1)")
    private Long startValue;

    @Schema(description = "Step size; v1 enforces 1")
    private Integer incrementStep;

    @Schema(description = "Last allocated value; next = current_value + step")
    private Long currentValue;

    @Schema(description = "Reset cadence")
    private ResetCadence resetCadence;

    @Schema(description = "Period key of the last reset, e.g. \"2026\" / \"2026-04\"")
    private String lastResetKey;

    @Schema(description = "Allocation mode")
    private SequenceMode mode;

    @Schema(description = "Description")
    private String description;
}

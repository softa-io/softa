package io.softa.starter.metadata.entity;

import java.io.Serial;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.metadata.enums.ResetCadence;
import io.softa.starter.metadata.enums.SequenceMode;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Sequence generator configuration and counter.
 * Each row holds a single (tenant, code) counter plus its rendering template
 * and reset / mode policy. Allocation goes through
 * {@code io.softa.framework.orm.sequence.SequenceService} (port in softa-orm,
 * implementation in this starter).
 *
 * <p>v1 hard rules (by convention — config-API enforcement is deferred):
 * <ul>
 *   <li>{@code code} matches {@code "<ModelName>.<fieldName>"} for
 *       fields participating in auto-fill (see SequenceProcessor).</li>
 *   <li>{@code incrementStep == 1}.</li>
 *   <li>{@code code} is not changed after creation; rows are provisioned via
 *       the framework's {@code loadPreTenantData} on JSON files for tenant
 *       bootstrap, not created ad hoc through the API.</li>
 * </ul>
 *
 * <p>There is no {@code status} column: a row's existence equals "active".
 * Emergency disable is a DBA-only operation (DELETE the row, optionally
 * preserving {@code currentValue} elsewhere if a later restoration is
 * needed).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Sequence",
        businessKey = {"code"},
        multiTenant = true,
        copyable = false,
        description = "Sequence generator configuration and counter"
)
@Index(fields = {"tenantId", "code"}, unique = true,
        message = "A sequence with this code already exists.")
public class SysSequence extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Sequence Code", required = true,
            description = "Sequence code, e.g. \"Employee.code\"")
    private String code;

    @Field(required = true,
            description = "Format template, e.g. EMP-{yyyy}-{seq:5}")
    private String template;

    @Field(required = true,
            description = "First number after each reset (default 1)")
    private Long startValue;

    @Field(required = true, description = "Step size; v1 enforces 1")
    private Integer incrementStep;

    @Field(required = true,
            description = "Last allocated value; next = current_value + step")
    private Long currentValue;

    @Field(required = true)
    private ResetCadence resetCadence;

    @Field(description = "Period key of the last reset, e.g. \"2026\" / \"2026-04\"")
    private String lastResetKey;

    @Field(label = "Allocation Mode", required = true)
    private SequenceMode mode;

    @Field(length = 256)
    private String description;
}

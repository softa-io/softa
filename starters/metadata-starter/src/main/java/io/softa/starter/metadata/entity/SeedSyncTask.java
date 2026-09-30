package io.softa.starter.metadata.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.enums.SeedSyncTaskStatus;

/**
 * One tenant in a seed sync batch: the tenant seed files of the batch brought up to date in that tenant,
 * in one transaction. The unit a failure rolls back and a retry re-runs.
 *
 * <p>Shared (not multi-tenant): written by the platform about every tenant, and {@code tenantId} is a plain
 * column.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Seed Sync Task", idStrategy = IdStrategy.DISTRIBUTED_LONG)
@Index(fields = {"batchId", "tenantId"}, unique = true)
public class SeedSyncTask extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Batch", fieldType = FieldType.MANY_TO_ONE, relatedModel = SeedSyncBatch.class)
    private Long batchId;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Tenant", length = 64, description = "The tenant's code when the task was created")
    private String tenantCode;

    @Field(required = true)
    private SeedSyncTaskStatus status;

    @Field(label = "Attempt", description = "How many times the task was started, redeliveries included")
    private Integer attempt;

    @Field(label = "New Rows", description = "Rows created or adopted from the seed files")
    private Integer newRowCount;

    @Field(label = "Pushed", description = "Changes the manifest pushes into rows the tenant already had")
    private Integer pushedCount;

    @Field(label = "Changes", fieldType = FieldType.TEXT,
            description = "This tenant's own tenant file changes (JSON), when they differ from the batch's: the "
                    + "batch's plus files it is due and never had, loaded whole")
    private String changes;

    @Field(label = "File Results", fieldType = FieldType.TEXT,
            description = "Per file: rows created, adopted, skipped, pushed, removed, and rows not applied (JSON)")
    private String fileResults;

    @Field(label = "Start Time")
    private LocalDateTime startTime;

    @Field(label = "End Time")
    private LocalDateTime endTime;

    @Field(label = "Error", fieldType = FieldType.TEXT)
    private String errorSummary;
}

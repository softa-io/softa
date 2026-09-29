package io.softa.starter.metadata.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.enums.SeedSyncStatus;
import io.softa.starter.metadata.enums.SeedSyncTriggerType;

/**
 * One run of the seed sync: the platform files it loaded, then the tenants it brought up to date with the
 * tenant files that changed, one {@link SeedSyncTask} each. A run that finds nothing to do is not recorded.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Seed Sync Batch", idStrategy = IdStrategy.DISTRIBUTED_LONG)
public class SeedSyncBatch extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(required = true)
    private SeedSyncStatus status;

    @Field
    private SeedSyncTriggerType triggerType;

    @Field(label = "Retry Of", fieldType = FieldType.MANY_TO_ONE, relatedModel = SeedSyncBatch.class,
            description = "The batch whose failed tenants this one re-runs")
    private Long retryOfBatchId;

    @Field(label = "Files", fieldType = FieldType.TEXT,
            description = "Platform files to load, comma-separated, in load order")
    private String fileNames;

    @Field(label = "Total Files")
    private Integer totalFiles;

    @Field(label = "Loaded Files")
    private Integer loadedFiles;

    @Field(label = "Tenant Files", fieldType = FieldType.TEXT,
            description = "Tenant files whose added or removed rows reach tenants, comma-separated, in load order")
    private String tenantFileNames;

    @Field(label = "Tenant Changes", fieldType = FieldType.TEXT,
            description = "Per tenant file, the rows this release added and removed (JSON) — what each tenant gets")
    private String tenantChanges;

    @Field(label = "Selected Tenants", fieldType = FieldType.TEXT,
            description = "Tenant ids the batch was limited to, comma-separated; empty for every tenant. A limited "
                    + "batch leaves the tenant files pending, for the sync that reaches everyone")
    private String selectedTenantIds;

    @Field(label = "Tenants")
    private Integer totalTenants;

    @Field(label = "Succeeded Tenants")
    private Integer succeededTenants;

    @Field(label = "Failed Tenants")
    private Integer failedTenants;

    @Field(label = "Skipped Tenants")
    private Integer skippedTenants;

    @Field(label = "Application Version", length = 64)
    private String appVersion;

    @Field(label = "Build Time", description = "Build time of the application that ran the batch")
    private LocalDateTime buildTime;

    @Field(label = "Start Time")
    private LocalDateTime startTime;

    @Field(label = "End Time")
    private LocalDateTime endTime;

    @Field(label = "Error", fieldType = FieldType.TEXT)
    private String errorMessage;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = SeedFileVersion.class, relatedField = "batchId",
            description = "File versions this batch loaded")
    private List<SeedFileVersion> versions;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedModel = SeedSyncTask.class, relatedField = "batchId",
            description = "One per tenant brought up to date")
    private List<SeedSyncTask> tasks;
}

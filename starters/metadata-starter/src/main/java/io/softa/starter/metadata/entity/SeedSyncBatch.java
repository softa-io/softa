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

/**
 * One run of the platform seed sync: which platform files it set out to load, how far it got, and how it
 * ended. A run that finds nothing to load is not recorded.
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

    @Field(label = "Files", fieldType = FieldType.TEXT, description = "Files to load, comma-separated, in load order")
    private String fileNames;

    @Field(label = "Total Files")
    private Integer totalFiles;

    @Field(label = "Loaded Files")
    private Integer loadedFiles;

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
}

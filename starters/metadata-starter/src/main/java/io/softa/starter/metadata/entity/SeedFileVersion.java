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

/**
 * A version of a platform seed file that was loaded into this database: the file's content hash, and
 * when and by which build it was loaded. The row with the latest {@code syncedTime} is what the database
 * holds now; a running application whose file hashes differ has changes still to sync.
 *
 * <p>One row per distinct content. Content that comes back — a later release restoring an earlier
 * version of the file — updates the existing row rather than adding one.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Seed File Version", idStrategy = IdStrategy.DISTRIBUTED_LONG)
@Index(fields = {"fileName", "checksum"}, unique = true)
public class SeedFileVersion extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "File Name", required = true, length = 128)
    private String fileName;

    @Field(label = "Checksum", required = true, length = 64, description = "SHA-256 of the file content")
    private String checksum;

    @Field(label = "Application Version", length = 64)
    private String appVersion;

    @Field(label = "Build Time", description = "Build time of the application that loaded this version")
    private LocalDateTime buildTime;

    @Field(label = "Changelog", fieldType = FieldType.TEXT, description = "Changelog of the file's package")
    private String changelog;

    @Field(label = "Synced Time")
    private LocalDateTime syncedTime;

    @Field(label = "Batch", fieldType = FieldType.MANY_TO_ONE, relatedModel = SeedSyncBatch.class)
    private Long batchId;
}

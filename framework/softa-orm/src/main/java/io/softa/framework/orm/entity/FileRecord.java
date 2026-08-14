package io.softa.framework.orm.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.enums.FileSource;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * FileRecord Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        softDelete = true
)
@Index(fields = {"tenantId", "modelName", "rowId"})
public class FileRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(required = true, length = 128)
    private String fileName;

    @Field(label = "OSS Key", length = 128)
    private String ossKey;

    @Field
    private FileType fileType;

    @Field(label = "File Size(KB)")
    private Integer fileSize;

    @Field
    private String checksum;

    @Field
    private String modelName;

    @Field(label = "Row ID")
    private String rowId;

    @Field
    private String fieldName;

    @Field
    private FileSource source;

    @Field
    private Boolean deleted;
}

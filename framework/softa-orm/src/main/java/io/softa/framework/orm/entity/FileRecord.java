package io.softa.framework.orm.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.enums.FileSource;
import io.softa.framework.orm.enums.FileType;

/**
 * FileRecord Model
 */
@Data
@Schema(name = "FileRecord")
@EqualsAndHashCode(callSuper = true)
public class FileRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "File Name")
    private String fileName;

    @Schema(description = "OSS Key")
    private String ossKey;

    @Schema(description = "File Type")
    private FileType fileType;

    @Schema(description = "File Size(KB)")
    private Integer fileSize;

    @Schema(description = "Checksum")
    private String checksum;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "Row ID")
    private String rowId;

    @Schema(description = "Field Name")
    private String fieldName;

    @Schema(description = "Source")
    private FileSource source;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
package io.softa.starter.file.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;

/**
 * ExportHistory Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(multiTenant = true, copyable = false)
@Index(fields = {"tenantId", "createdTime"})
public class ExportHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Template ID", fieldType = FieldType.MANY_TO_ONE, relatedModel = ExportTemplate.class)
    private Long templateId;

    @Field(label = "Import Template ID", fieldType = FieldType.MANY_TO_ONE, relatedModel = ImportTemplate.class,
            description = "The import template a file for re-import was written with")
    private Long importTemplateId;

    @Field
    private String modelName;

    @Field(label = "Exported File ID", fieldType = FieldType.FILE, required = true)
    private Long exportedFileId;

    @Field
    private Integer totalRows;

    @Field(label = "Duration in seconds")
    private Double duration;

    @Field
    private Boolean deleted;
}

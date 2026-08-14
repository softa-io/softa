package io.softa.starter.file.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.file.enums.DocumentTemplateType;

/**
 * DocumentTemplate Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(multiTenant = true)
public class DocumentTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field
    private String modelName;

    @Field(length = 128)
    private String fileName;

    @Field
    private DocumentTemplateType templateType;

    @Field(label = "File Template ID")
    private Long fileId;

    @Field(label = "HTML Template Content", fieldType = FieldType.TEXT)
    private String htmlTemplate;

    @Field(label = "Convert To PDF")
    private Boolean convertToPdf;

    @Field(length = 256)
    private String description;
}

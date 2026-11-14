package io.softa.starter.file.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * DocumentTemplateSignSlot Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(multiTenant = true, defaultOrder = {"templateId", "sequence"})
public class DocumentTemplateSignSlot extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Document Template")
    private Long templateId;

    @Field
    private String slotName;

    @Field
    private String slotCode;

    @Field
    private Integer sequence;

    @Field
    private JsonNode placement;

    @Field(length = 256)
    private String description;
}

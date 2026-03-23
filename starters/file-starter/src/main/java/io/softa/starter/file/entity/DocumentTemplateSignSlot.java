package io.softa.starter.file.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * DocumentTemplateSignSlot Model
 */
@Data
@Schema(name = "DocumentTemplateSignSlot")
@EqualsAndHashCode(callSuper = true)
public class DocumentTemplateSignSlot extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "Document Template")
    private Long templateId;

    @Schema(description = "Slot Name")
    private String slotName;

    @Schema(description = "Slot Code")
    private String slotCode;

    @Schema(description = "sequence")
    private Integer sequence;

    @Schema(description = "Placement")
    private JsonNode placement;

    @Schema(description = "Description")
    private String description;
}
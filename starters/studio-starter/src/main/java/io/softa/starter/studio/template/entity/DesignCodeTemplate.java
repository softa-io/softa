package io.softa.starter.studio.template.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * DesignCodeTemplate Model
 */
@Data
@Schema(name = "DesignCodeTemplate")
@EqualsAndHashCode(callSuper = true)
public class DesignCodeTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Code Language")
    private DesignCodeLang codeLang;

    @Schema(description = "Sequence")
    private Integer sequence;

    @Schema(description = "Sub Directory")
    private String subDirectory;

    @Schema(description = "File Name Placeholder")
    private String fileName;

    @Schema(description = "Template Content")
    private String templateContent;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}

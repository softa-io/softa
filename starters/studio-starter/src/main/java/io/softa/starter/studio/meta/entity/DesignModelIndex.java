package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * DesignModelIndex Model
 */
@Data
@Schema(name = "DesignModelIndex")
@EqualsAndHashCode(callSuper = true)
public class DesignModelIndex extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Portfolio")
    private Long portfolioId;

    @Schema(description = "APP ID")
    private Long appId;

    @Schema(description = "Model ID")
    private Long modelId;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "Index Title")
    private String name;

    @Schema(description = "Index Name")
    private String indexName;

    @Schema(description = "Index Fields")
    private List<String> indexFields;

    @Schema(description = "Is Unique Index")
    private Boolean uniqueIndex;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
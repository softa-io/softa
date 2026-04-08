package io.softa.starter.studio.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * DTO for a generated model code file.
 */
@Data
@Schema(name = "Model Code File")
public class ModelCodeFileDTO {

    @Schema(description = "Template ID when generated from DesignCodeTemplate")
    private Long templateId;

    @Schema(description = "Template name or source")
    private String templateName;

    @Schema(description = "Template sequence")
    private Integer sequence;

    @Schema(description = "Generated sub directory")
    private String subDirectory;

    @Schema(description = "Generated file name")
    private String fileName;

    @Schema(description = "Generated relative path")
    private String relativePath;

    @Schema(description = "Generated file content")
    private String content;
}

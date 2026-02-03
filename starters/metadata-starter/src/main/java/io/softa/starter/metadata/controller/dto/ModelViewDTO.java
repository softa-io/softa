package io.softa.starter.metadata.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Simple DTO of modelName and view ID
 */
@Schema(name = "ModelViewDTO")
@Data
public class ModelViewDTO {

    @Schema(description = "Model name")
    @NotBlank(message = "The model name cannot be empty!")
    private String model;

    @Schema(description = "View ID")
    @NotNull(message = "The view ID cannot be null!")
    private Long viewId;

}

package io.softa.starter.ai.entity;

import java.io.Serial;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.ai.enums.AiModelProvider;
import io.softa.starter.ai.enums.AiModelType;

/**
 * AiModel Model
 */
@Data
@Schema(name = "AiModel")
@EqualsAndHashCode(callSuper = true)
public class AiModel extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Model Name")
    private String name;

    @Schema(description = "Model Code")
    private String code;

    @Schema(description = "Model Provider")
    private AiModelProvider modelProvider;

    @Schema(description = "Model Type")
    private AiModelType modelType;

    @Schema(description = "Input Price/1M tokens")
    private BigDecimal unitPriceInput;

    @Schema(description = "Output price/1M tokens")
    private BigDecimal unitPriceOutput;

    @Schema(description = "Max Context Tokens")
    private Integer maxTokens;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Active")
    private Boolean active;
}
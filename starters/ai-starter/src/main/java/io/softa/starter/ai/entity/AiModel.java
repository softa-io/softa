package io.softa.starter.ai.entity;

import java.io.Serial;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.ai.enums.AiModelProvider;
import io.softa.starter.ai.enums.AiModelType;

/**
 * AiModel Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "AI Model",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        softDelete = true,
        activeControl = true,
        defaultOrder = {"name"}
)
public class AiModel extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Model Name", required = true)
    private String name;

    @Field(label = "Model Code", required = true)
    private String code;

    @Field
    private AiModelProvider modelProvider;

    @Field
    private AiModelType modelType;

    @Field(label = "Input Price/1M tokens")
    private BigDecimal unitPriceInput;

    @Field(label = "Output price/1M tokens")
    private BigDecimal unitPriceOutput;

    @Field(label = "Max Context Tokens")
    private Integer maxTokens;

    @Field(label = "API Base URL", length = 256)
    private String baseUrl;

    @Field(label = "API Key", length = 512, encrypted = true, copyable = false, unsearchable = true)
    private String apiKey;

    @Field(label = "Request Timeout (ms)")
    private Integer timeout;

    @Field(length = 256)
    private String description;

    @Field
    private Boolean active;

    @Field
    private Boolean deleted;
}

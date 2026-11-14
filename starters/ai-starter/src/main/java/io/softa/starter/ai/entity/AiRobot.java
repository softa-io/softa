package io.softa.starter.ai.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.ai.enums.AiModelProvider;

/**
 * AiRobot Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "AI Robot",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        softDelete = true,
        activeControl = true,
        description = "Robots that pre-define system prompts and parameter configurations.",
        defaultOrder = {"name"}
)
public class AiRobot extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Robot Name", required = true)
    private String name;

    @Field(label = "Robot Code")
    private String code;

    @Field(label = "AI Model ID", fieldType = FieldType.MANY_TO_ONE, relatedModel = AiModel.class, required = true)
    private Long aiModelId;

    @Field(label = "AI Model Code", cascadedField = "aiModelId.code",
            description = "Denormalized mirror of ai_model.code via the aiModelId relation — "
                    + "framework-maintained stored cascade (readonly, drift-free). Lets list/query "
                    + "views show the model code without joining ai_model.")
    private String aiModel;

    @Field(label = "AI Provider", cascadedField = "aiModelId.modelProvider",
            description = "Denormalized mirror of ai_model.model_provider via the aiModelId relation — "
                    + "framework-maintained stored cascade (readonly, drift-free).")
    private AiModelProvider aiProvider;

    @Field(fieldType = FieldType.TEXT)
    private String systemPrompt;

    @Field(label = "Model Max Context Tokens", cascadedField = "aiModelId.maxTokens",
            description = "Denormalized mirror of ai_model.max_tokens (the model's context window) "
                    + "via the aiModelId relation — framework-maintained stored cascade (readonly, "
                    + "drift-free). A model property, not a per-call generation param.")
    private Integer modelMaxTokens;

    @Field
    private Integer outputTokensLimit;

    @Field
    private Double temperature;

    @Field(label = "Enable Stream Output")
    private Boolean stream;

    @Field
    private Double presencePenalty;

    @Field
    private Double frequencyPenalty;

    @Field(length = 256)
    private String description;

    @Field
    private Boolean active;

    @Field
    private Boolean deleted;
}

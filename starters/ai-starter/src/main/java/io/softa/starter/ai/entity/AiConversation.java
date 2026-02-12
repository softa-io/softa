package io.softa.starter.ai.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * AiConversation Model
 */
@Data
@Schema(name = "AiConversation")
@EqualsAndHashCode(callSuper = true)
public class AiConversation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Conversation Title")
    private String title;

    @Schema(description = "Robot ID")
    private Long robotId;

    @Schema(description = "Total Tokens")
    private Integer totalTokens;

    @Schema(description = "Description")
    private String description;

}
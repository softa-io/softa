package io.softa.starter.ai.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.ai.enums.AiMessageRole;
import io.softa.starter.ai.enums.AiMessageStatus;

/**
 * AiMessage Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "AI Message",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        copyable = false
)
public class AiMessage extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Robot ID")
    private Long robotId;

    @Field(label = "Conversation ID", required = true)
    private Long conversationId;

    @Field(required = true)
    private AiMessageRole role;

    @Field(fieldType = FieldType.TEXT)
    private String content;

    @Field(label = "Input Tokens",
            description = "Prompt (input) tokens of the turn this message belongs to. Populated on the "
                    + "ASSISTANT message from the provider's reported usage (the response carries the "
                    + "whole turn's usage); user-role rows stay 0.")
    private Integer inputTokens;

    @Field(label = "Output Tokens",
            description = "Completion (output) tokens of the turn. Populated on the ASSISTANT message; "
                    + "user-role rows stay 0.")
    private Integer outputTokens;

    @Field(label = "Stream Output")
    private Boolean stream;

    @Field(label = "Parent Message ID")
    private Long parentId;

    @Field
    private AiMessageStatus status;
}

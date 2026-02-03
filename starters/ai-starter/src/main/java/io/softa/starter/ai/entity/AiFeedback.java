package io.softa.starter.ai.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * AiFeedback Model
 */
@Data
@Schema(name = "AiFeedback")
@EqualsAndHashCode(callSuper = true)
public class AiFeedback extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Conversation ID")
    private String conversationId;

    @Schema(description = "Message ID")
    private String messageId;

    @Schema(description = "Feedback Content")
    private String feedback;

}
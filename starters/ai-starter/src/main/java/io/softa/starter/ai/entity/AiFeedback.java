package io.softa.starter.ai.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

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
    private Long id;

    @Schema(description = "Conversation ID")
    private Long conversationId;

    @Schema(description = "Message ID")
    private Long messageId;

    @Schema(description = "Feedback Content")
    private String feedback;

}
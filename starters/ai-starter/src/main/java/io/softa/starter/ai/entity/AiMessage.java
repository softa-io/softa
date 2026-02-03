package io.softa.starter.ai.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.ai.enums.AiMessageRole;
import io.softa.starter.ai.enums.AiMessageStatus;

/**
 * AiMessage Model
 */
@Data
@Schema(name = "AiMessage")
@EqualsAndHashCode(callSuper = true)
public class AiMessage extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Robot ID")
    private String robotId;

    @Schema(description = "Conversation ID")
    private String conversationId;

    @Schema(description = "Role")
    private AiMessageRole role;

    @Schema(description = "Content")
    private String content;

    @Schema(description = "Tokens")
    private Integer tokens;

    @Schema(description = "Stream Output")
    private Boolean stream;

    @Schema(description = "Parent Message ID")
    private String parentId;

    @Schema(description = "Status")
    private AiMessageStatus status;




}
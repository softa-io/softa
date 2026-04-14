package io.softa.starter.message.inbox.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.message.inbox.enums.TodoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * Actionable todo item assigned to a specific user.
 * <p>
 * Todo items require the assignee to take an explicit action (approve, review, etc.).
 * They carry a reference to the originating object ({@code sourceType} / {@code sourceId})
 * so that the handler can navigate to the relevant entity, and an {@code actionUrl}
 * for direct deep-linking from the inbox UI.
 * <p>
 * Integration point: {@code flow-starter} calls
 * {@link io.softa.starter.message.inbox.service.InboxService#createTodo} to generate
 * approval/review todos from workflow nodes.
 */
@Data
@Schema(name = "InboxTodo")
@EqualsAndHashCode(callSuper = true)
public class InboxTodo extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Assignee user ID")
    private Long assigneeId;

    @Schema(description = "Todo title")
    private String title;

    @Schema(description = "Detailed description of the required action")
    private String description;

    @Schema(description = "Source object type, e.g. FLOW_INSTANCE (nullable)")
    private String sourceType;

    @Schema(description = "Source object ID (nullable)")
    private Long sourceId;

    @Schema(description = "Deep-link URL to the page where the assignee handles this todo")
    private String actionUrl;

    @Schema(description = "Todo lifecycle status: Pending, Done, Rejected, Expired")
    private TodoStatus status;

    @Schema(description = "Optional due date")
    private LocalDateTime dueAt;

    @Schema(description = "Timestamp when the todo was completed or rejected")
    private LocalDateTime completedAt;
}

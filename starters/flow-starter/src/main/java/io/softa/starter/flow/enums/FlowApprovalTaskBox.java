package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Which of a user's own tasks an approval center list shows ({@code POST /flow/approvalTasks/search}). Not stored:
 * each box is a fixed condition on the task's type and status.
 */
@Getter
@AllArgsConstructor
public enum FlowApprovalTaskBox {

    /** Approval tasks waiting on the user. Newest first unless the request sorts otherwise. */
    PENDING("Pending"),

    /**
     * Approval tasks that closed, whatever closed them — the user's own decision, another approver's vote, a
     * withdrawal, a termination. Latest closed first unless the request sorts otherwise.
     */
    HANDLED("Handled"),

    /** CC tasks, read and unread. Newest first unless the request sorts otherwise. */
    CC("Cc");

    @JsonValue
    private final String type;
}

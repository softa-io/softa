package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code SEND_INBOX_NOTIFICATION} nodes.
 * <p>
 * Fields hold the raw authored values ({@code {{ expr }}} placeholders included);
 * resolution is owned by the executor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendInboxNotificationConfig {

    /** Recipient user ids: a list of ids, or a {@code {{ expr }}} resolving to one (required). */
    private Object recipientIds;

    /** Notification title (required). */
    private String title;

    /** Notification content (required). */
    private String content;

    /** Notification type name; defaults to WORKFLOW. */
    private String notificationType;
}

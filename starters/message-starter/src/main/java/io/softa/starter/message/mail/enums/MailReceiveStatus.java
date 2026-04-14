package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Status of an incoming mail record.
 */
@Getter
@AllArgsConstructor
public enum MailReceiveStatus {
    UNREAD("Unread", "Not yet read"),
    READ("Read", "Opened by user"),
    ARCHIVED("Archived", "Moved to archive"),
    DELETED("Deleted", "Marked for deletion");

    @JsonValue
    private final String code;
    private final String description;
}

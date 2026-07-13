package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Status of an incoming mail record.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum MailReceiveStatus {
    @OptionItem(description = "Not yet read")
    UNREAD("Unread"),
    @OptionItem(description = "Opened by user")
    READ("Read"),
    @OptionItem(description = "Moved to archive")
    ARCHIVED("Archived"),
    @OptionItem(description = "Marked for deletion")
    DELETED("Deleted");

    @JsonValue
    private final String code;
}

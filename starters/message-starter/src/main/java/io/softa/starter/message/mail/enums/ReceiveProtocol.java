package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Incoming mail protocol. The choice determines fetch semantics:
 * IMAP variants are non-destructive (server state untouched, incremental via UID watermark);
 * POP3 variants are destructive (each fetched message is deleted from the server).
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum ReceiveProtocol {
    @OptionItem(label = "IMAP",
            description = "IMAP over plain TCP. Non-destructive: server state (read flags, mailbox layout) "
                    + "is never modified. Incremental fetch tracks IMAP UID per folder.")
    IMAP("IMAP"),
    @OptionItem(label = "IMAPS",
            description = "IMAP over TLS. Same non-destructive semantics as IMAP.")
    IMAPS("IMAPS"),
    @OptionItem(label = "POP3",
            description = "POP3 over plain TCP. DESTRUCTIVE: each fetched message is deleted from the server "
                    + "(DELE) and is no longer accessible via webmail. Use only for system-exclusive mailboxes.")
    POP3("POP3"),
    @OptionItem(label = "POP3S",
            description = "POP3 over TLS. Same destructive semantics as POP3.")
    POP3S("POP3S");

    @JsonValue
    private final String code;

    public boolean isImap() {
        return this == IMAP || this == IMAPS;
    }

    public boolean isPop3() {
        return this == POP3 || this == POP3S;
    }
}

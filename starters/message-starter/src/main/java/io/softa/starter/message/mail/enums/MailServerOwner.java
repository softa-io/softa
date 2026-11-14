package io.softa.starter.message.mail.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;

/**
 * Whose mail server carried a send: the tenant's own, or the platform's. Recorded on the send record
 * with the server's id, which is unique only within its owner's table, and the two together are what
 * a retry replays. A tenant's mail goes out through its own default server or, when it has none, is
 * relayed through the platform's; the platform's mail only ever through the platform's.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum MailServerOwner {
    @OptionItem(label = "Tenant Server", description = "Sent through one of the tenant's own send servers")
    TENANT("Tenant"),
    @OptionItem(label = "Platform Server", description = "Sent through a platform send server: the platform's "
            + "own mail, or a tenant's relayed because the tenant has no send server of its own")
    PLATFORM("Platform");

    @JsonValue
    private final String code;
}

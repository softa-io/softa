package io.softa.framework.base.context;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;

/**
 * Basic info of the current user
 */
@Data
public class UserInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String name;
    private Language language;
    private Timezone timezone;
    private String photoUrl;
    private Long tenantId;

    /**
     * The person the account belonged to when this was built. A session names the person it was
     * opened for, and a closed account reopened for someone else must not carry the old person's
     * sessions into it, so the two are compared per request. Nullable: an entry cached before this
     * field existed, or an account nobody has joined yet, compares as nothing.
     */
    private Long profileId;

    /**
     * Whether the account may operate. Re-checked per request in {@code ContextBuilder}
     * so a frozen / off-boarded account is force-logged-out even mid-session. Nullable:
     * a legacy UserInfo serialized before this field existed deserializes to {@code null}
     * and is treated as active, so a deploy does not mass-logout existing sessions.
     */
    private Boolean active;

}

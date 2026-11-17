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

    /** File id of the user's photo. The cached UserInfo holds this, never an address. */
    private Long photoId;

    /**
     * Address of the user's photo: a key only this account can open, valid 30 to 31 days. Filled when
     * the UserInfo is returned to its user and always null in the cache — an address stored there
     * would outlive what signed it.
     */
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
     * Display name of the tenant, so the client can show which company the session is in. Null
     * when the deployment has no tenant names.
     *
     * <p>Set when the UserInfo is built and cached with the rest of it, so a renamed tenant shows
     * its new name only once the cached entry is rebuilt.
     */
    private String tenantName;

    /**
     * Whether the account may operate. Re-checked per request in {@code ContextBuilder}
     * so a frozen / off-boarded account is force-logged-out even mid-session. Nullable:
     * a legacy UserInfo serialized before this field existed deserializes to {@code null}
     * and is treated as active, so a deploy does not mass-logout existing sessions.
     */
    private Boolean active;

}

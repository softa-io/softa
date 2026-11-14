package io.softa.framework.web.session;

/**
 * Why a session stopped working. Carried on {@link SessionRevokedEvent} so listeners (login
 * history, push-device clean-up) can tell an ordinary logout from a forced one.
 */
public enum SessionRevokeReason {
    /** The person logged out. */
    LOGOUT,
    /** The person switched to another tenant, which replaces the session. */
    SWITCHED_TENANT,
    /** The person left the tenant for the company step. */
    LEFT_TENANT,
    /** The account became Frozen or Deactivated, or its tenant stopped being active. */
    ORG_UNAVAILABLE,
    /** Another device of the same person changed the password or the login mobile. */
    KICKED_BY_OTHER_DEVICE,
    /** The password was reset through forgot-password. */
    PASSWORD_RESET,
    /** HR ran Reset User on the person. */
    RESET_USER,
    /** HR unbound the account from its person to re-invite someone. */
    UNBOUND
}

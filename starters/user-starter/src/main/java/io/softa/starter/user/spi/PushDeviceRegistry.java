package io.softa.starter.user.spi;

/**
 * Push-device registrations, owned by whichever module sends APP push notifications.
 *
 * <p>The account flows only need to drop registrations — on logout (this device), on Reset User and
 * password reset (every device of the person), and when an account is frozen or closed (its
 * devices) — so that is all this asks. Optional: without a push module there is
 * nothing registered and nothing to drop. A module that also wants registrations gone whenever a
 * session ends listens for {@code SessionRevokedEvent} instead.
 */
public interface PushDeviceRegistry {

    /**
     * Forget one device token — only when it is registered to this person. The token arrives in a
     * request body, and a caller must not be able to drop someone else's device by naming it.
     */
    void unregisterDevice(String deviceToken, Long profileId);

    /** Forget every device registered to any account of this person. */
    void unregisterPerson(Long profileId);

    /**
     * Forget every device registered to this account: it was frozen or closed, and a notification
     * from a company the person can no longer enter must not reach them. Does nothing by default, so
     * a registry written before this method existed still compiles.
     */
    default void unregisterAccount(Long accountId) {
    }
}

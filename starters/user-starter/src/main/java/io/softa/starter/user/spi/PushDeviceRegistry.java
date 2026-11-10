package io.softa.starter.user.spi;

/**
 * What the account flows need of the push-device registrations, implemented by {@code AppPushDeviceServiceImpl}.
 *
 * <p>The account flows only need to drop registrations — on logout (this device), on Reset User and
 * password reset (every device of the person), and when an account is frozen or closed (its
 * devices) — so that is all this asks. Registrations also go whenever a session ends, through
 * {@code SessionRevokedEvent}.
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
     * from a company the person can no longer enter must not reach them.
     */
    void unregisterAccount(Long accountId);
}

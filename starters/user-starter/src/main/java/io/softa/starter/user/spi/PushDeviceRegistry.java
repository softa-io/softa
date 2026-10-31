package io.softa.starter.user.spi;

/**
 * Push-device registrations, owned by whichever module sends APP push notifications.
 *
 * <p>The account flows only need to drop registrations — on logout (this device) and on Reset User
 * (every device of the person) — so that is all this asks. Optional: without a push module there is
 * nothing registered and nothing to drop. A module that also wants registrations gone whenever a
 * session ends listens for {@code SessionRevokedEvent} instead.
 */
public interface PushDeviceRegistry {

    /** Forget one device token, whoever it was registered to. */
    void unregisterDevice(String deviceToken);

    /** Forget every device registered to any account of this person. */
    void unregisterPerson(Long profileId);
}

package io.softa.starter.message.push.service;

import java.util.Collection;
import java.util.List;

import io.softa.starter.message.push.entity.AppPushDevice;
import io.softa.framework.orm.service.EntityService;

/**
 * The APP's push-device registrations: which devices an account is signed in on, kept with the session it signed in
 * under. A sender reads {@link #liveDevices}; the account flows call the {@code unregister} methods on logout, Reset
 * User, password reset, and when an account is frozen or closed. Registrations also go whenever a session ends, through
 * {@code SessionRevokedEvent}.
 */
public interface AppPushDeviceService extends EntityService<AppPushDevice, Long> {

    /**
     * Register this device for the account signed in on this request. A token already registered —
     * to this account or any other — is moved here and its details refreshed.
     */
    void register(String platform, String deviceToken, String appVersion, String deviceModel, String locale);

    /** Drop a registration of the person signed in; a token registered to anyone else is left alone. */
    void unregisterMine(String deviceToken);

    /**
     * The devices to notify for these accounts: those whose session may still be alive. A sender reads
     * through this rather than the table, so a device whose session ended on its own gets nothing.
     */
    List<AppPushDevice> liveDevices(Collection<Long> accountIds);

    /**
     * The push service said this token no longer exists. Only while it is still this account's: it may have just moved
     * to whoever signed in on the device, and that registration is good.
     */
    void dropInvalid(String deviceToken, Long accountId);

    /** Remove the registrations whose session has certainly ended; returns how many. */
    int dropStale();

    /**
     * Forget one device token — only when it is registered to this person. The token arrives in a request body, and a
     * caller must not be able to drop someone else's device by naming it.
     */
    void unregisterDevice(String deviceToken, Long profileId);

    /** Forget every device registered to any account of this person. */
    void unregisterPerson(Long profileId);

    /**
     * Forget every device registered to this account: it was frozen or closed, and a notification from a company the
     * person can no longer enter must not reach them.
     */
    void unregisterAccount(Long accountId);
}

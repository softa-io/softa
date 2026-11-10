package io.softa.starter.user.service;

import java.util.Collection;
import java.util.List;

import io.softa.starter.user.entity.AppPushDevice;
import io.softa.framework.orm.service.EntityService;

/**
 * The APP's push-device registrations: which devices an account is signed in on, kept with the session it signed in
 * under. A sender reads {@link #liveDevices}; the account flows drop registrations through {@code PushDeviceRegistry},
 * which this also is.
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
}

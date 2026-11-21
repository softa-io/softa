package io.softa.starter.message.push;

/**
 * Sends one notification to one device through APNs or FCM. It knows nothing of which devices exist or what the
 * notification is about: that is the application's. Each platform is enabled on its own by having its credentials
 * configured, and with none configured nothing is sent.
 */
public interface PushSender {

    /** Whether this platform has usable credentials. */
    boolean available(PushPlatform platform);

    /** Whether any platform does: an application with nothing to send through can skip the work altogether. */
    default boolean anyAvailable() {
        return available(PushPlatform.IOS) || available(PushPlatform.ANDROID);
    }

    /**
     * Sends one notification, blocking until the service answers or the request times out. A failure is a result,
     * never an exception.
     *
     * @throws IllegalStateException when the platform is not {@link #available}
     */
    PushResult send(PushRequest request);
}

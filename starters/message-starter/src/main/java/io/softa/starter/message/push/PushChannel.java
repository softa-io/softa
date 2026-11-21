package io.softa.starter.message.push;

import java.io.IOException;
import java.util.regex.Pattern;

/** One service's way of sending. */
interface PushChannel {

    /**
     * What both services put in a device token: hex digits for APNs, a few more characters for FCM. A token is
     * written into the request URL (APNs) or JSON, so anything else is refused before it is sent.
     */
    Pattern TOKEN = Pattern.compile("[A-Za-z0-9:_\\-]{1,512}");

    /**
     * Sends one notification and classifies the service's answer.
     *
     * @throws IOException the service could not be reached, its token endpoint could not, or it answered that it is
     *                     in trouble as a whole ({@link ServiceTrouble})
     * @throws InterruptedException the thread was interrupted while waiting for the service
     */
    PushResult send(PushRequest request) throws IOException, InterruptedException;

    /**
     * The service answered 5xx: it is in trouble as a whole, not this device. Counted with the network failures
     * towards pausing the platform.
     */
    final class ServiceTrouble extends IOException {
        ServiceTrouble(String reason) {
            super(reason);
        }
    }
}

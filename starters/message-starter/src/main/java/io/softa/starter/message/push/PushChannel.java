package io.softa.starter.message.push;

import java.io.IOException;
import java.time.Duration;
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
     * @throws IOException the service could not be reached, or its token endpoint could not
     * @throws InterruptedException the thread was interrupted while waiting for the service
     */
    Sent send(PushRequest request) throws IOException, InterruptedException;

    /**
     * The result for the caller, and how long the whole platform should wait when the service said it is in trouble
     * as a whole — never for one device's limit, which is that device's to wait out.
     */
    record Sent(PushResult result, Duration pausePlatform) {

        static Sent of(PushResult result) {
            return new Sent(result, null);
        }
    }
}

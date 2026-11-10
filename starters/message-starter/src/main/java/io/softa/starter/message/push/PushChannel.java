package io.softa.starter.message.push;

import java.io.IOException;

/** One service's way of sending. */
interface PushChannel {

    /**
     * Sends one notification and classifies the service's answer.
     *
     * @throws IOException the service could not be reached, or its token endpoint could not
     * @throws InterruptedException the thread was interrupted while waiting for the service
     */
    PushResult send(PushRequest request) throws IOException, InterruptedException;
}

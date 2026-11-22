package io.softa.starter.message.push;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps one platform's trouble to itself: its own limit on requests in flight, and a pause once the service stops
 * answering or keeps answering 5xx. While paused every send fails at once, so a blocked network costs about
 * {@link #FAILURES_BEFORE_PAUSE} timeouts per {@link #PAUSE}, not one per device. One device's rate limit is that
 * device's failure and pauses nothing.
 */
@Slf4j
final class GuardedChannel {

    static final int FAILURES_BEFORE_PAUSE = 10;
    static final Duration PAUSE = Duration.ofMinutes(1);

    private final PushPlatform platform;
    private final PushChannel channel;
    private final Semaphore permits;
    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Pause pause = new Pause(Instant.MIN, null);

    GuardedChannel(PushPlatform platform, PushChannel channel, int concurrency, Clock clock) {
        this.platform = platform;
        this.channel = channel;
        this.permits = new Semaphore(concurrency);
        this.clock = clock;
    }

    PushResult send(PushRequest request) {
        Pause current = pause;
        if (clock.instant().isBefore(current.until())) {
            return PushResult.failed("Paused until " + current.until() + " after: " + current.cause());
        }
        try {
            permits.acquire();
            try {
                PushResult result = channel.send(request);
                consecutiveFailures.set(0);
                return result;
            } finally {
                permits.release();
            }
        } catch (IOException e) {
            return failed(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushResult.failed("Interrupted");
        } catch (RuntimeException e) {
            // A send never throws (PushSender): whatever this request carried that the channel could not handle,
            // the platform is fine.
            return PushResult.rejected(describe(e));
        }
    }

    /** A timeout, a connection refused or reset, a 5xx: nothing here says the token or the request is at fault. */
    private PushResult failed(IOException e) {
        String reason = describe(e);
        if (consecutiveFailures.incrementAndGet() >= FAILURES_BEFORE_PAUSE) {
            consecutiveFailures.set(0);
            pause = new Pause(clock.instant().plus(PAUSE), reason);
            log.error("Push: {} failed {} times in a row, pausing for {}: {}", platform, FAILURES_BEFORE_PAUSE, PAUSE,
                    reason);
        }
        return PushResult.failed(reason);
    }

    /** "HTTP 503 UNAVAILABLE" for a service's answer; the exception's type and message for anything else. */
    static String describe(Exception e) {
        if (e instanceof PushChannel.ServiceTrouble) {
            return e.getMessage();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private record Pause(Instant until, String cause) {
    }
}

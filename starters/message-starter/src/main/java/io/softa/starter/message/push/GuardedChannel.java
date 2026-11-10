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
 * answering or says to wait. While paused every send returns at once, so a blocked network costs one timeout per
 * {@link #PAUSE}, not one per device.
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
    private volatile Instant pausedUntil = Instant.MIN;

    GuardedChannel(PushPlatform platform, PushChannel channel, int concurrency, Clock clock) {
        this.platform = platform;
        this.channel = channel;
        this.permits = new Semaphore(concurrency);
        this.clock = clock;
    }

    PushResult send(PushRequest request) {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return PushResult.retry(Duration.between(now, pausedUntil), "Paused after failures");
        }
        try {
            permits.acquire();
            try {
                PushResult result = channel.send(request);
                consecutiveFailures.set(0);
                if (result.retryAfter() != null) {
                    pauseFor(result.retryAfter());
                }
                return result;
            } finally {
                permits.release();
            }
        } catch (IOException e) {
            return failed(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushResult.retry(null, "Interrupted");
        }
    }

    /** A timeout, a connection refused or reset: nothing here says the token or the request is at fault. */
    private PushResult failed(IOException e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (consecutiveFailures.incrementAndGet() >= FAILURES_BEFORE_PAUSE) {
            consecutiveFailures.set(0);
            pauseFor(PAUSE);
            log.error("Push: {} could not be reached {} times in a row, pausing for {}: {}",
                    platform, FAILURES_BEFORE_PAUSE, PAUSE, reason);
        }
        return PushResult.retry(null, reason);
    }

    private void pauseFor(Duration duration) {
        pausedUntil = clock.instant().plus(duration);
    }
}

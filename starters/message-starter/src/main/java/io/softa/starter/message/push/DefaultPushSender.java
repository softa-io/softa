package io.softa.starter.message.push;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The {@link PushSender}: routes each platform to its channel. A platform whose credentials are missing or unusable is
 * off, and says so once at startup; a bad key never stops the application from starting.
 */
@Slf4j
@Component
public class DefaultPushSender implements PushSender {

    /** Both services take at most 4 KB, and an approval comment can be far longer. */
    static final int MAX_BODY_BYTES = 1000;
    /** A lock screen shows a line or two of title; the rest only costs payload room. */
    static final int MAX_TITLE_BYTES = 200;
    /** How long the services keep trying an offline device when the request does not say. */
    static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofHours(24);
    private static final Duration CONFIG_ERROR_LOG_INTERVAL = Duration.ofMinutes(5);

    private final boolean logOnly;
    private final Clock clock;
    private final Map<PushPlatform, GuardedChannel> channels = new EnumMap<>(PushPlatform.class);
    private final Map<PushPlatform, Instant> configErrorLoggedAt = new ConcurrentHashMap<>();

    @Autowired
    public DefaultPushSender(PushProperties properties) {
        this(properties, Clock.systemUTC());
    }

    DefaultPushSender(PushProperties properties, Clock clock) {
        this.logOnly = properties.isLogOnly();
        this.clock = clock;
        if (logOnly) {
            log.warn("Push: log-only is on, notifications are logged and not sent");
            return;
        }
        if (StringUtils.isNotBlank(properties.getApns().getPrivateKey())) {
            enable(properties, PushPlatform.IOS, () -> new ApnsPushChannel(properties.getApns(),
                    properties.getConnectTimeout(), properties.getRequestTimeout(), clock));
        } else {
            log.info("Push: APNs is not configured, iOS devices get no notifications");
        }
        if (StringUtils.isNotBlank(properties.getFcm().getServiceAccountJson())) {
            enable(properties, PushPlatform.ANDROID, () -> new FcmPushChannel(properties.getFcm(),
                    properties.getConnectTimeout(), properties.getRequestTimeout(), clock));
        } else {
            log.info("Push: FCM is not configured, Android devices get no notifications");
        }
    }

    private void enable(PushProperties properties, PushPlatform platform, ChannelFactory factory) {
        try {
            channels.put(platform, new GuardedChannel(platform, factory.create(), properties.getConcurrency(), clock));
            log.info("Push: {} is enabled", platform);
        } catch (GeneralSecurityException | RuntimeException e) {
            // Never the key's content in the log: only what kind of thing went wrong.
            log.error("Push: {} credentials are unusable, it stays off: {}", platform, e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean available(PushPlatform platform) {
        return logOnly || channels.containsKey(platform);
    }

    @Override
    public PushResult send(PushRequest request) {
        PushRequest fitted = new PushRequest(request.platform(), request.deviceToken(),
                fit(request.title(), MAX_TITLE_BYTES), fit(request.body(), MAX_BODY_BYTES), request.data(),
                request.dedupeId(), request.threadId(),
                request.timeToLive() == null || request.timeToLive().isNegative() ? DEFAULT_TIME_TO_LIVE
                        : request.timeToLive());
        if (logOnly) {
            // What the notification says can be personal (an approval comment, whose payslip): only at DEBUG.
            log.info("Push (log only): platform={} token=...{} dedupeId={} threadId={}",
                    fitted.platform(), StringUtils.right(fitted.deviceToken(), 6), fitted.dedupeId(), fitted.threadId());
            log.debug("Push (log only) content: title={} body={} data={}", fitted.title(), fitted.body(), fitted.data());
            return PushResult.sent();
        }
        GuardedChannel channel = channels.get(request.platform());
        if (channel == null) {
            throw new IllegalStateException("Push is not available for " + request.platform());
        }
        PushResult result = channel.send(fitted);
        if (result.outcome() == PushResult.Outcome.CONFIG_ERROR) {
            logConfigError(request.platform(), result.reason());
        }
        return result;
    }

    /** A wrong key fails every send: once in a while is enough to be noticed. */
    private void logConfigError(PushPlatform platform, String reason) {
        Instant now = clock.instant();
        Instant last = configErrorLoggedAt.get(platform);
        if (last == null || last.plus(CONFIG_ERROR_LOG_INTERVAL).isBefore(now)) {
            configErrorLoggedAt.put(platform, now);
            log.error("Push: {} refused the configured credentials or target: {}", platform, reason);
        }
    }

    /** {@code text} cut to at most {@code maxBytes} of UTF-8, ellipsis included, never inside a character. */
    static String fit(String text, int maxBytes) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        String ellipsis = "…";
        ByteBuffer room = ByteBuffer.allocate(maxBytes - ellipsis.getBytes(StandardCharsets.UTF_8).length);
        CharBuffer source = CharBuffer.wrap(text);
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        encoder.encode(source, room, true);
        return text.substring(0, source.position()) + ellipsis;
    }

    @FunctionalInterface
    private interface ChannelFactory {
        PushChannel create() throws GeneralSecurityException;
    }
}

package io.softa.starter.message.push;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

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

    /**
     * About what a lock screen shows of a body, four lines or so: an approval comment can be far longer, and the rest
     * is the inbox message's to show.
     */
    static final int MAX_BODY_CHARS = 100;
    /** A lock screen shows a line or two of title and cuts the rest itself; this only keeps the payload in bounds. */
    static final int MAX_TITLE_BYTES = 200;
    /** How long the services keep trying an offline device when the request does not say. */
    static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofHours(24);

    private final boolean logOnly;
    private final Clock clock;
    private final Map<PushPlatform, GuardedChannel> channels = new EnumMap<>(PushPlatform.class);

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
                fit(request.title(), MAX_TITLE_BYTES), trim(request.body(), MAX_BODY_CHARS), request.data(),
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
        // The caller logs every outcome but success, with the notification it was for.
        return channel.send(fitted);
    }

    /**
     * {@code text} on one line — runs of line breaks and spaces made one space — and cut to {@code maxChars}
     * characters, ellipsis included, never inside a character; null or blank stays null.
     */
    static String trim(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        String line = text.replaceAll("\\s+", " ").strip();
        if (line.isEmpty()) {
            return null;
        }
        if (line.codePointCount(0, line.length()) <= maxChars) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, maxChars - 1)).stripTrailing() + "…";
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

package io.softa.starter.message.push.queue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.push.PushPlatform;
import io.softa.starter.message.push.PushRequest;
import io.softa.starter.message.push.PushResult;
import io.softa.starter.message.push.PushSender;
import io.softa.starter.message.push.entity.AppPushDevice;
import io.softa.starter.message.push.service.AppPushDeviceService;

/**
 * Sends one queued inbox message to its recipient's live devices, once: a token the service disowns loses its
 * registration, and every other failure is logged as an ERROR with its cause. Nothing is tried again and nothing is
 * recorded beyond the log; the inbox message holds what the push said. The log never carries the title, the body or a
 * whole token.
 *
 * <p>The push says the message's title and its content, cut to what a lock screen shows (the sender's), the content
 * left out when it is empty or the title again. It carries the message and what it refers to — {@code notificationId},
 * {@code tenantId}, {@code sourceModel}, {@code sourceId} — and the APP decides from the model which page opens.
 */
@Slf4j
@Component
public class AppPushProcessor {

    /** How long the services keep trying a device that is offline: a day-old reminder is no reminder. */
    static final Duration TIME_TO_LIVE = Duration.ofHours(24);

    private final PushSender pushSender;
    private final AppPushDeviceService deviceService;

    public AppPushProcessor(PushSender pushSender, AppPushDeviceService deviceService) {
        this.pushSender = pushSender;
        this.deviceService = deviceService;
    }

    /** Handles one message in its tenant's context; never throws, so the broker never redelivers it. */
    public void process(AppPushMessage message) {
        if (message == null || message.notificationId() == null || message.tenantId() == null
                || message.recipientId() == null) {
            return;
        }
        Context context = new Context(message.traceId());
        context.setTenantId(message.tenantId());
        context.setSkipPermissionCheck(true);
        try {
            ContextHolder.runWith(context, () -> send(message));
        } catch (RuntimeException e) {
            log.error("Push: notification={} tenant={} not processed: {}", message.notificationId(),
                    message.tenantId(), e.toString());
        }
    }

    private void send(AppPushMessage message) {
        if (!pushSender.anyAvailable()) {
            return;
        }
        List<AppPushDevice> devices = deviceService.liveDevices(List.of(message.recipientId()));
        if (devices.isEmpty()) {
            log.info("Push: notification={} tenant={} has no live device", message.notificationId(), message.tenantId());
            return;
        }
        int sent = 0;
        int failed = 0;
        for (AppPushDevice device : devices) {
            PushPlatform platform = platformOf(device.getPlatform());
            // A platform without credentials skips its devices quietly: it is off, not failing.
            if (platform == null || !pushSender.available(platform)) {
                continue;
            }
            PushResult result = pushSender.send(requestFor(message, device, platform));
            switch (result.outcome()) {
                case SENT -> sent++;
                case TOKEN_INVALID -> {
                    log.info("Push: notification={} tenant={} platform={} token=...{} is no longer valid ({}), "
                                    + "registration dropped", message.notificationId(), message.tenantId(), platform,
                            StringUtils.right(device.getDeviceToken(), 6), result.reason());
                    deviceService.dropInvalid(device.getDeviceToken(), device.getAccountId());
                }
                default -> {
                    failed++;
                    log.error("Push: notification={} tenant={} platform={} token=...{} {}: {}",
                            message.notificationId(), message.tenantId(), platform,
                            StringUtils.right(device.getDeviceToken(), 6), result.outcome(), result.reason());
                }
            }
        }
        log.info("Push: notification={} tenant={} sent to {} device(s), {} failed", message.notificationId(),
                message.tenantId(), sent, failed);
    }

    static PushRequest requestFor(AppPushMessage message, AppPushDevice device, PushPlatform platform) {
        String id = String.valueOf(message.notificationId());
        String body = StringUtils.isBlank(message.content()) || StringUtils.equals(message.title(), message.content())
                ? null : message.content();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("notificationId", id);
        data.put("tenantId", String.valueOf(message.tenantId()));
        if (message.sourceModel() != null) {
            data.put("sourceModel", message.sourceModel());
        }
        if (message.sourceId() != null) {
            data.put("sourceId", message.sourceId());
        }
        return new PushRequest(platform, device.getDeviceToken(), message.title(), body, data, id,
                message.sourceModel(), TIME_TO_LIVE);
    }

    private static PushPlatform platformOf(String platform) {
        return switch (StringUtils.defaultString(platform)) {
            case "ios" -> PushPlatform.IOS;
            case "android" -> PushPlatform.ANDROID;
            default -> null;
        };
    }
}

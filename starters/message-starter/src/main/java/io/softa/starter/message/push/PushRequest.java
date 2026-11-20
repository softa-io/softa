package io.softa.starter.message.push;

import java.time.Duration;
import java.util.Map;

/**
 * One notification to one device.
 *
 * @param platform    the service the device receives from
 * @param deviceToken the device's token at that service
 * @param title       the notification title
 * @param body        the notification body; null to send a title alone. Cut to fit the services' payload limit
 * @param data        what the app reads when the notification is opened; string values only
 * @param dedupeId    the same id delivered twice shows once on the device; at most 64 bytes
 * @param threadId    notifications sharing it are grouped on the device; may be null
 * @param timeToLive  how long the service keeps trying a device that is offline
 */
public record PushRequest(PushPlatform platform, String deviceToken, String title, String body,
                          Map<String, String> data, String dedupeId, String threadId, Duration timeToLive) {
}

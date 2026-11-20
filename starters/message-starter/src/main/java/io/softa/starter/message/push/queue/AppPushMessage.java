package io.softa.starter.message.push.queue;

/**
 * One inbox message to push, as queued on {@code push-send}. It carries what the push says, so sending never reads
 * the notification back: a push goes out seconds after the message is written, and is never tried again.
 *
 * @param notificationId the inbox notification
 * @param tenantId       its tenant
 * @param recipientId    the account it is for
 * @param title          its title
 * @param content        its content
 * @param sourceModel    the model of the object it refers to; may be null
 * @param sourceId       the id of that object, as text; may be null
 * @param traceId        the trace of the request that wrote it; may be null
 */
public record AppPushMessage(Long notificationId, Long tenantId, Long recipientId, String title, String content,
                             String sourceModel, String sourceId, String traceId) {
}

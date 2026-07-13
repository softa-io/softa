package io.softa.starter.flow.runtime.spi;

/**
 * SPI for sending notifications during the approval lifecycle.
 *
 * <p>Implementations translate each {@link FlowNotificationEvent} into outbox-backed
 * messages via {@code message-starter} ({@code MessageService.sendMail},
 * {@code MessageService.sendSms}, IM channels). Synchronous external side effects
 * (direct {@code RestTemplate}, raw {@code JavaMailSender}) bypass outbox retry/DLQ
 * and block the engine transaction — don't.</p>
 */
public interface ApprovalNotificationService {

    void notify(FlowNotificationEvent event);
}

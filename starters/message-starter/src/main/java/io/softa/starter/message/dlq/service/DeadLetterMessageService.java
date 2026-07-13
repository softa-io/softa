package io.softa.starter.message.dlq.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.dlq.entity.DeadLetterMessage;
import io.softa.starter.message.shared.ErrorCategory;

/**
 * Dead Letter Message Model Service Interface
 */
public interface DeadLetterMessageService extends EntityService<DeadLetterMessage, Long> {

    /**
     * Archive a mail / SMS send record that exhausted its provider retry budget
     * into the unified {@code dead_letter_message} store, tagged
     * {@code source = SEND_EXHAUSTED}. The record id is carried in
     * {@code eventId} and the failure detail in the JSON payload.
     */
    void archiveSendExhausted(String channel, Long recordId, String provider,
                              String errorCode, String errorMessage,
                              ErrorCategory category, int attempts);
}

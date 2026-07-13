package io.softa.starter.message.dlq.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.dlq.entity.DeadLetterMessage;
import io.softa.starter.message.dlq.enums.DeadLetterSource;
import io.softa.starter.message.dlq.enums.DeadLetterStatus;
import io.softa.starter.message.dlq.service.DeadLetterMessageService;
import io.softa.starter.message.shared.ErrorCategory;


/**
 * Dead Letter Message Model Service Implementation
 */
@Service
public class DeadLetterMessageServiceImpl extends EntityServiceImpl<DeadLetterMessage, Long>
        implements DeadLetterMessageService {

    /** Marker event type for send-exhausted rows (broker rows carry the real business event type). */
    private static final String SEND_EVENT_TYPE = "SEND_DEAD_LETTER";

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void archiveSendExhausted(String channel, Long recordId, String provider,
                                     String errorCode, String errorMessage,
                                     ErrorCategory category, int attempts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("provider", provider);
        payload.put("recordId", recordId);
        payload.put("errorCode", errorCode);
        payload.put("errorMessage", errorMessage);
        payload.put("category", category != null ? category.name() : null);
        payload.put("attempts", attempts);

        DeadLetterMessage record = DeadLetterMessage.builder()
                .source(DeadLetterSource.SEND_EXHAUSTED)
                .sourceTenantId(currentTenantId())
                .originalTopic(channel)          // the send channel, e.g. "mail" / "sms"
                .dlqTopic("")                    // no broker DLQ involved
                .eventType(SEND_EVENT_TYPE)
                .eventId(recordId)               // FK back to mail_send_record / sms_send_record
                .payload(objectMapper.valueToTree(payload))
                .status(DeadLetterStatus.PENDING)
                .lastErrorMsg(errorMessage)
                .build();
        createOne(record);
    }

    private static Long currentTenantId() {
        Context ctx = ContextHolder.getContext();
        return ctx != null ? ctx.getTenantId() : null;
    }
}

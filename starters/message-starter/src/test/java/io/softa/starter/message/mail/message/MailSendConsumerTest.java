package io.softa.starter.message.mail.message;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;
import io.softa.starter.message.mq.outbox.OutboxMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The send consumer is a thin wrapper: it must forward the outbox
 * {@code recordId} to {@link MailDeliveryProcessor#process(Long)} and run it
 * inside the tenant/trace context carried by the message.
 */
class MailSendConsumerTest {

    @Test
    void onSend_delegatesToProcessor_insideTenantContext() {
        MailDeliveryProcessor processor = mock(MailDeliveryProcessor.class);
        MailSendConsumer consumer = new MailSendConsumer();
        ReflectionTestUtils.setField(consumer, "deliveryProcessor", processor);

        AtomicReference<Long> tenantDuringProcessing = new AtomicReference<>();
        doAnswer(inv -> {
            Context ctx = ContextHolder.getContext();
            tenantDuringProcessing.set(ctx == null ? null : ctx.getTenantId());
            return null;
        }).when(processor).process(5L);

        consumer.onSend(new OutboxMessage(5L, 7L, "trace-1"));

        verify(processor).process(5L);
        assertEquals(Long.valueOf(7L), tenantDuringProcessing.get(),
                "consumer must restore the tenant context from the outbox message");
    }
}

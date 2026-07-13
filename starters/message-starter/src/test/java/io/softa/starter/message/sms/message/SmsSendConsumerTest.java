package io.softa.starter.message.sms.message;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.mq.outbox.OutboxMessage;
import io.softa.starter.message.sms.service.impl.SmsDeliveryProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SMS send consumer mirrors the mail one: forward the {@code recordId} to the
 * CAS-guarded {@link SmsDeliveryProcessor#process(Long)} inside the message's
 * tenant/trace context.
 */
class SmsSendConsumerTest {

    @Test
    void onSend_delegatesToProcessor_insideTenantContext() {
        SmsDeliveryProcessor processor = mock(SmsDeliveryProcessor.class);
        SmsSendConsumer consumer = new SmsSendConsumer();
        ReflectionTestUtils.setField(consumer, "deliveryProcessor", processor);

        AtomicReference<Long> tenantDuringProcessing = new AtomicReference<>();
        doAnswer(inv -> {
            Context ctx = ContextHolder.getContext();
            tenantDuringProcessing.set(ctx == null ? null : ctx.getTenantId());
            return null;
        }).when(processor).process(11L);

        consumer.onSend(new OutboxMessage(11L, 42L, "trace-sms"));

        verify(processor).process(11L);
        assertEquals(Long.valueOf(42L), tenantDuringProcessing.get(),
                "consumer must restore the tenant context from the outbox message");
    }
}

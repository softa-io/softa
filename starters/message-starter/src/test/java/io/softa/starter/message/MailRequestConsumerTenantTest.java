package io.softa.starter.message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.shared.TenantScopes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The tenant the consumer restores before rendering — which is also the tenant the send record is
 * stamped with, since nothing sets it explicitly and the ORM reads it off the context.
 *
 * <p>Asserting on the message, or on "sendMail was called", would pass with any of this deleted.
 * What matters is the ambient tenant INSIDE sendMail.
 */
class MailRequestConsumerTenantTest {

    private static final Long TENANT = 42L;

    private MessageService messageService;
    private MailRequestConsumer consumer;
    /** The ambient tenant observed from inside sendMail. */
    private AtomicReference<Long> tenantSeenBySend;

    @BeforeEach
    void setUp() {
        messageService = mock(MessageService.class);
        consumer = new MailRequestConsumer(messageService);
        tenantSeenBySend = new AtomicReference<>();
        doAnswer(inv -> {
            tenantSeenBySend.set(ContextHolder.getContext().getTenantId());
            return null;
        }).when(messageService).sendMail(any(SendMailDTO.class));
    }

    private static MailRequestMessage request(Long tenantId, MessageScope scope) {
        return new MailRequestMessage(List.of("someone@example.test"), "user.verification-code",
                Map.of("code", "123456", "expiryMinutes", 5), tenantId, scope);
    }

    @Test
    void aTenantScopedRequestRendersUnderTheTenantItNames() {
        consumer.onMessage(request(TENANT, MessageScope.TENANT));

        assertThat(tenantSeenBySend.get()).isEqualTo(TENANT);
    }

    @Test
    void aPlatformScopedRequestIsFiledUnderThePlatformTier() {
        // A login / forgot-password code names no tenant, because none is known before a session
        // exists. Left unpinned, the record this send writes is stamped from an empty context —
        // tenant_id NULL — and then belongs to nobody: no tenant filter matches it, so the one
        // person who asked for the mail cannot see whether it went out. The rest of the pipeline
        // already treats the send as the platform's (template, server and quota all resolve there);
        // the record was the only part left behind.
        consumer.onMessage(request(null, MessageScope.PLATFORM));

        assertThat(tenantSeenBySend.get()).isEqualTo(TenantScopes.PLATFORM);
    }

    @Test
    void aTenantScopedRequestWithNoTenantStaysUnpinnedSoTheProducerBugStaysVisible() {
        // TENANT scope with no tenant is a producer that had one and failed to pass it. Pinning it
        // to the platform tier here would render the platform's copy under a tenant's name and make
        // the mistake look like a working send.
        consumer.onMessage(request(null, MessageScope.TENANT));

        assertThat(tenantSeenBySend.get()).isNull();
    }

    @Test
    void theThreadIsLeftAsItWasFound() {
        // Listener threads are pooled. A tenant left behind would be inherited by whatever message
        // this thread picks up next.
        Context ambient = new Context();
        ambient.setTenantId(7L);
        ContextHolder.runWith(ambient, () -> {
            consumer.onMessage(request(null, MessageScope.PLATFORM));
            assertThat(ContextHolder.getContext().getTenantId()).isEqualTo(7L);
        });
    }
}

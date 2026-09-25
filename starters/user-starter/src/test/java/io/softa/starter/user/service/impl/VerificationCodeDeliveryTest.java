package io.softa.starter.user.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.message.SmsRequestMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import io.softa.starter.user.service.ConsultantService;

/**
 * The verification code has to actually leave the building (finding #3).
 *
 * <p>sendEmailCode / sendMobileCode used to generate and store a code and then stop at a TODO, so
 * every code-based path — join, code login, code reset — dead-ended at "we sent you a code" that
 * was never sent. These assert the request message goes out, on the right channel, carrying the
 * code the guard stored.
 */
class VerificationCodeDeliveryTest {

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    VerificationCodeDeliveryTest() {
        ReflectionTestUtils.setField(loginService, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
    }

    @Test
    void anEmailCode_isMailedOnThePlatformTier() {
        // No tenant context exists before a session, so the platform template is the code's copy.
        loginService.sendEmailCode("alice@acme.com");

        ArgumentCaptor<MailRequestMessage> sent = ArgumentCaptor.forClass(MailRequestMessage.class);
        verify(eventPublisher).publishEvent(sent.capture());
        assertThat(sent.getValue().to()).containsExactly("alice@acme.com");
        assertThat(sent.getValue().templateCode()).isEqualTo("user.verification-code");
        assertThat(sent.getValue().variables()).containsKey("code");
        assertThat(sent.getValue().scope()).isEqualTo(MessageScope.PLATFORM);
    }

    @Test
    void aMobileCode_isTexted() {
        loginService.sendMobileCode("+8613800138000");

        ArgumentCaptor<SmsRequestMessage> sent = ArgumentCaptor.forClass(SmsRequestMessage.class);
        verify(eventPublisher).publishEvent(sent.capture());
        assertThat(sent.getValue().to()).containsExactly("+8613800138000");
        assertThat(sent.getValue().templateCode()).isEqualTo("user.verification-code");
        assertThat(sent.getValue().variables()).containsKey("code");
    }
}

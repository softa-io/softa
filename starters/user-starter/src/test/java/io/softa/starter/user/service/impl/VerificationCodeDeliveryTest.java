package io.softa.starter.user.service.impl;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    VerificationCodeDeliveryTest() {
        ReflectionTestUtils.setField(loginService, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        // The default for these two: the address belongs to somebody. The refusal cases below
        // override it.
        when(identityService.findByLoginIdentifier(anyString()))
                .thenReturn(Optional.of(new UserIdentity()));
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

    @Test
    void anEmailNoAccountCanSignInWith_isRefusedInsteadOfReportedSent() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.sendEmailCode("nobody@nowhere.test"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This email is not linked to any account. Please contact your administrator.");

        // Nothing addressed to it, and nothing stored against it: the refusal must not spend the
        // address's send budget or overwrite a code its real owner may still be typing.
        verify(eventPublisher, never()).publishEvent(any());
        verify(codeGuard, never()).beforeSend(anyString());
        verify(codeGuard, never()).store(anyString(), anyString());
    }

    @Test
    void aMobileNoAccountCanSignInWith_isRefusedWithItsOwnWording() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.sendMobileCode("+8613900000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This mobile number is not linked to any account. Please contact your administrator.");

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * /join sends to an invitee who has no identity yet — that is what joining is — so the guard
     * on the public entry points must not reach this path. Reinstating it here would refuse every
     * first-time joiner, and nothing else in the suite would notice.
     */
    @Test
    void aJoinCode_isSentEvenThoughTheInviteeHasNoIdentityYet() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        UserInvitationService invitations = mock(UserInvitationService.class);
        ReflectionTestUtils.setField(loginService, "invitationService", invitations);
        when(invitations.resolveJoinChannel("tok", "email")).thenReturn("newcomer@acme.com");

        loginService.sendJoinCode("tok", "email");

        ArgumentCaptor<MailRequestMessage> sent = ArgumentCaptor.forClass(MailRequestMessage.class);
        verify(eventPublisher).publishEvent(sent.capture());
        assertThat(sent.getValue().to()).containsExactly("newcomer@acme.com");
    }
}

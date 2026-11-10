package io.softa.starter.user.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import io.softa.starter.user.service.ConsultantService;

/**
 * The verification code has to actually leave the building (finding #3).
 *
 * <p>sendEmailCode / sendMobileCode used to generate and store a code and then stop at a TODO, so
 * every code-based path — join, code login, code reset — dead-ended at "we sent you a code" that
 * was never sent. These assert the send is requested, on the right channel, carrying the code the
 * guard stored.
 */
class VerificationCodeDeliveryTest {

    private final MessageService messageService = mock(MessageService.class);
    private final VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    VerificationCodeDeliveryTest() {
        ReflectionTestUtils.setField(loginService, "messageService", messageService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        // These cases are about what a LINKED identifier gets; an unlinked one is ACCOUNT_NOT_FOUND
        // (IdentifierResolutionTest).
        io.softa.starter.user.service.UserAccountService accountService =
                mock(io.softa.starter.user.service.UserAccountService.class);
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        UserIdentity linked = new UserIdentity();
        linked.setProfileId(7L);
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.of(linked));
        when(accountService.listAccountIdsOf(7L)).thenReturn(java.util.List.of(100L));
    }

    @Test
    void anEmailCode_isMailedOnThePlatformTier() {
        // No tenant context exists before a session, so the platform template is the code's copy.
        loginService.sendEmailCode("alice@acme.com");

        ArgumentCaptor<SendMailDTO> sent = ArgumentCaptor.forClass(SendMailDTO.class);
        verify(messageService).sendMail(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("alice@acme.com");
        assertThat(sent.getValue().getTemplateCode()).isEqualTo("user.verification-code");
        assertThat(sent.getValue().getTemplateVariables()).containsKey("code");
        assertThat(sent.getValue().getScope()).isEqualTo(MessageScope.PLATFORM);
    }

    @Test
    void aMobileCode_isTextedOnThePlatformTier() {
        // Same reason as the email code: no session, so no tenant to render or charge it to.
        loginService.sendMobileCode("+8613800138000");

        ArgumentCaptor<SendSmsDTO> sent = ArgumentCaptor.forClass(SendSmsDTO.class);
        verify(messageService).sendSms(sent.capture());
        assertThat(sent.getValue().getPhoneNumber()).isEqualTo("+8613800138000");
        assertThat(sent.getValue().getTemplateCode()).isEqualTo("user.verification-code");
        assertThat(sent.getValue().getTemplateVariables()).containsKey("code");
        assertThat(sent.getValue().getScope()).isEqualTo(MessageScope.PLATFORM);
    }

    @Test
    void aSendMessageStarterRejects_isAnsweredLikeAnyOther() {
        // A number on file that does not parse: logged at ERROR, never told to the caller — the
        // screen answers the same whether or not the number is anybody's.
        when(messageService.sendSms(any())).thenThrow(new BusinessException("Invalid E.164 phone number"));

        assertDoesNotThrow(() -> loginService.sendMobileCode("+8613800138000"));
    }
}

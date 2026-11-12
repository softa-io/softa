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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import io.softa.framework.base.context.ContextHolder;
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
    private final io.softa.starter.user.service.UserAccountService accountService =
            mock(io.softa.starter.user.service.UserAccountService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    VerificationCodeDeliveryTest() {
        ReflectionTestUtils.setField(loginService, "messageService", messageService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        // These cases are about what a LINKED identifier gets; an unlinked one is ACCOUNT_NOT_FOUND
        // (IdentifierResolutionTest).
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
        // Same reason as the email code: no session, so no tenant to render it from.
        loginService.sendMobileCode("+8613800138000");

        ArgumentCaptor<SendSmsDTO> sent = ArgumentCaptor.forClass(SendSmsDTO.class);
        verify(messageService).sendSms(sent.capture());
        assertThat(sent.getValue().getPhoneNumber()).isEqualTo("+8613800138000");
        assertThat(sent.getValue().getTemplateCode()).isEqualTo("user.verification-code");
        assertThat(sent.getValue().getTemplateVariables()).containsKey("code");
        assertThat(sent.getValue().getScope()).isEqualTo(MessageScope.PLATFORM);
    }

    @Test
    void aCodeForSomeoneInOneCompany_isThatCompanysSend() {
        // Filed under the company and counted against its quota, though the platform renders it.
        when(accountService.soleJoinedTenantOf(7L)).thenReturn(Optional.of(42L));
        List<Long> sentIn = new ArrayList<>();
        when(messageService.sendMail(any())).thenAnswer(invocation -> {
            sentIn.add(ContextHolder.getContext().getTenantId());
            return 1L;
        });
        when(messageService.sendSms(any())).thenAnswer(invocation -> {
            sentIn.add(ContextHolder.getContext().getTenantId());
            return 2L;
        });

        loginService.sendEmailCode("alice@acme.com");
        loginService.sendMobileCode("+8613800138000");

        assertThat(sentIn).containsExactly(42L, 42L);
    }

    @Test
    void aCodeForSomeoneInSeveralCompanies_isThePlatformsSend() {
        // Neither company's: no tenant context, so message-starter files it under the platform.
        when(accountService.soleJoinedTenantOf(7L)).thenReturn(Optional.empty());
        List<Long> sentIn = new ArrayList<>();
        when(messageService.sendMail(any())).thenAnswer(invocation -> {
            sentIn.add(ContextHolder.getContext().getTenantId());
            return 1L;
        });

        loginService.sendEmailCode("alice@acme.com");

        assertThat(sentIn).hasSize(1).containsOnlyNulls();
    }

    @Test
    void theCodeIsStoredOutsideTheCompany_whereTheVerifyStepLooksForIt() {
        // Only the send runs in the company's context: a code cached under a tenant would never be
        // found by the sign-in that checks it, which has no tenant yet.
        when(accountService.soleJoinedTenantOf(7L)).thenReturn(Optional.of(42L));
        List<Long> storedIn = new ArrayList<>();
        doAnswer(invocation -> {
            storedIn.add(ContextHolder.getContext().getTenantId());
            return null;
        }).when(codeGuard).store(anyString(), anyString());

        loginService.sendEmailCode("alice@acme.com");

        assertThat(storedIn).hasSize(1).containsOnlyNulls();
    }

    @Test
    void aSendMessageStarterRejects_isAnsweredLikeAnyOther() {
        // A number on file that does not parse: logged at ERROR, never told to the caller — the
        // screen answers the same whether or not the number is anybody's.
        when(messageService.sendSms(any())).thenThrow(new BusinessException("Invalid E.164 phone number"));

        assertDoesNotThrow(() -> loginService.sendMobileCode("+8613800138000"));
    }
}

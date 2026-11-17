package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;

import static io.softa.starter.user.TestSystemConfigs.siteAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an invitation says, and that Send Invitation does not claim to have sent one no channel
 * could carry.
 */
class InvitationDeliveryTest {

    private static final Long TENANT = 200L;
    private static final Long USER_ID = 9001L;

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final TenantInfoService tenants = mock(TenantInfoService.class);
    private UserInvitationServiceImpl service;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        service = spy(new UserInvitationServiceImpl(accountService, mock(UserIdentityService.class), messageService,
                tenants, mock(JoinProofGuard.class), mock(VerificationCodeGuard.class),
                siteAt("https://app.example.test/")));
        doReturn(List.<UserInvitation>of()).when(service).searchList(any(Filters.class));
        doReturn(1L).when(service).createOne(any(UserInvitation.class));

        account = new UserAccount();
        account.setId(USER_ID);
        account.setTenantId(TENANT);
        account.setStatus(AccountStatus.PENDING);
        account.setEmail("ada@acme.com");
        account.setMobile("+6591234567");
        when(accountService.getById(USER_ID)).thenReturn(Optional.of(account));
        when(accountService.noticeNameOf(account)).thenReturn("Ada Lovelace");
        when(tenants.getTenantName(TENANT)).thenReturn("Acme Pte Ltd");
    }

    private void invite() {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        ContextHolder.runWith(ctx, () -> service.invite(USER_ID, null));
    }

    @Test
    void bothChannelsNameTheCompanyAndTheEmployee() {
        invite();

        ArgumentCaptor<SendMailDTO> mail = ArgumentCaptor.forClass(SendMailDTO.class);
        ArgumentCaptor<SendSmsDTO> sms = ArgumentCaptor.forClass(SendSmsDTO.class);
        verify(messageService).sendMail(mail.capture());
        verify(messageService).sendSms(sms.capture());
        Map<String, Object> variables = mail.getValue().getTemplateVariables();
        assertThat(variables).containsEntry("companyName", "Acme Pte Ltd")
                .containsEntry("employeeName", "Ada Lovelace")
                .containsEntry("expiryDays", 7)
                .containsKey("link");
        assertThat(sms.getValue().getTemplateVariables()).isEqualTo(variables);
    }

    @Test
    void whenNoChannelCanCarryIt_theInvitationIsRefused() {
        doThrow(new BusinessException("No send server is configured")).when(messageService).requireMailRoute(any());
        doThrow(new BusinessException("No SMS provider configured for region SG"))
                .when(messageService).requireSmsRoute(anyString());

        // Thrown, so the token and the PENDING → INVITED change roll back with it.
        assertThatThrownBy(this::invite)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Failed to send. Please try again.");
        verify(messageService, never()).sendMail(any());
        verify(messageService, never()).sendSms(any());
    }

    @Test
    void oneChannelThatCanCarryItIsEnough() {
        doThrow(new BusinessException("No SMS provider configured for region SG"))
                .when(messageService).requireSmsRoute(anyString());

        assertThatCode(this::invite).doesNotThrowAnyException();
        verify(messageService).sendMail(any());
        verify(messageService, never()).sendSms(any());
        assertThat(account.getStatus()).isEqualTo(AccountStatus.INVITED);
    }

    @Test
    void aNumberThatOnlySmsCanReach_isRefusedWhenSmsCannotCarryIt() {
        account.setEmail(null);
        doThrow(new BusinessException("No SMS provider configured for region SG"))
                .when(messageService).requireSmsRoute(anyString());

        assertThatThrownBy(this::invite).hasMessage("Failed to send. Please try again.");
    }

    @Test
    void provisioningKeepsTheInvitationWhateverTheChannels() {
        doThrow(new BusinessException("No send server is configured")).when(messageService).requireMailRoute(any());
        doThrow(new BusinessException("No SMS provider")).when(messageService).requireSmsRoute(anyString());
        Context ctx = new Context();
        ctx.setTenantId(TENANT);

        assertThatCode(() -> ContextHolder.runWith(ctx, () -> service.inviteWithoutDeliveryCheck(USER_ID, null)))
                .doesNotThrowAnyException();
        verify(service).createOne(any(UserInvitation.class));
    }
}

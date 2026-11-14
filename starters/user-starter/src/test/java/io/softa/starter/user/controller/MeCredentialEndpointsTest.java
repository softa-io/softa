package io.softa.starter.user.controller;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.MeCredentialRequests.ChangePasswordBody;
import io.softa.starter.user.dto.MeCredentialRequests.CheckNewMobileBody;
import io.softa.starter.user.dto.MeCredentialRequests.CodeBody;
import io.softa.starter.user.dto.MeCredentialRequests.ConfirmMobileBody;
import io.softa.starter.user.dto.MeCredentialRequests.TicketBody;
import io.softa.starter.user.dto.MeCredentialRequests.TicketView;
import io.softa.starter.user.dto.MeCredentialRequests.VerifyCurrentBody;
import io.softa.starter.user.service.MyCredentialService;
import io.softa.starter.user.service.impl.UiContextBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code /me/password/**} and {@code /me/mobile/**} endpoints hand each step to {@link MyCredentialService} as it
 * came, and wrap the ticket a step returns as {@code {"ticket": ...}}.
 */
@SuppressWarnings("unchecked")
class MeCredentialEndpointsTest {

    private final MyCredentialService credentials = mock(MyCredentialService.class);
    private final MeController controller = new MeController(mock(CacheService.class), mock(UiContextBuilder.class),
            mock(ObjectProvider.class), mock(ObjectProvider.class), credentials);

    @Test
    void changingThePasswordGoesCodeTicketChange() {
        when(credentials.verifyCodeForPassword("123456")).thenReturn("pw-ticket");

        controller.sendCode();
        ApiResponse<TicketView> verified = controller.verifyPasswordCode(new CodeBody("123456"));
        controller.changePassword(new ChangePasswordBody("pw-ticket", "N3w-password"));

        verify(credentials).sendCurrentMobileCode();
        assertThat(verified.getData().ticket()).isEqualTo("pw-ticket");
        verify(credentials).changePassword("pw-ticket", "N3w-password");
    }

    @Test
    void changingTheLoginMobileGoesProofNewNumberConfirm() {
        when(credentials.verifyCurrentForMobile("password", null, "secret")).thenReturn("proof");
        when(credentials.checkNewMobile("proof", "+6591234567")).thenReturn("new-number");

        ApiResponse<TicketView> proved = controller.verifyCurrent(new VerifyCurrentBody("password", null, "secret"));
        ApiResponse<TicketView> named = controller.checkNew(new CheckNewMobileBody("proof", "+6591234567"));
        controller.resendCode(new TicketBody("new-number"));
        controller.confirm(new ConfirmMobileBody("new-number", "654321"));

        assertThat(proved.getData().ticket()).isEqualTo("proof");
        assertThat(named.getData().ticket()).isEqualTo("new-number");
        verify(credentials).resendNewMobileCode("new-number");
        verify(credentials).confirmNewMobile("new-number", "654321");
    }

    @Test
    void theBodiesKeepTheirFieldNamesOnTheWire() {
        assertThat(JsonUtils.objectToString(new TicketView("t"))).isEqualTo("{\"ticket\":\"t\"}");
        VerifyCurrentBody proof = JsonUtils.stringToObject(
                "{\"method\":\"code\",\"verificationCode\":\"123456\"}", VerifyCurrentBody.class);
        assertThat(proof).isEqualTo(new VerifyCurrentBody("code", "123456", null));
        ChangePasswordBody change = JsonUtils.stringToObject(
                JsonUtils.objectToString(Map.of("ticket", "t", "newPassword", "p")), ChangePasswordBody.class);
        assertThat(change).isEqualTo(new ChangePasswordBody("t", "p"));
    }
}

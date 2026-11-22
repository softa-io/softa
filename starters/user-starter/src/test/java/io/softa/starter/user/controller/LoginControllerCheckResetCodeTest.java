package io.softa.starter.user.controller;

import java.util.Map;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.CheckResetCodeDTO;
import io.softa.starter.user.service.LoginService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The request the forgot-password page sends to check a code: its shape is the contract, and a field
 * read under another name arrives blank — which the server answers as "enter the code", whatever was
 * typed.
 */
class LoginControllerCheckResetCodeTest {

    private final LoginService loginService = mock(LoginService.class);
    private final LoginController controller = new LoginController();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    LoginControllerCheckResetCodeTest() {
        ReflectionTestUtils.setField(controller, "loginService", loginService);
    }

    @Test
    void readsTheIdentifierAndTheCode_andHandsThemToTheService() {
        CheckResetCodeDTO dto = JsonUtils.stringToObject(
                "{\"identifier\":\"+6591234567\",\"verificationCode\":\"123456\"}", CheckResetCodeDTO.class);

        ApiResponse<Void> reply = controller.checkResetCode(dto);

        assertThat(validator.validate(dto)).isEmpty();
        assertThat(reply.isSuccess()).isTrue();
        verify(loginService).checkResetCode("+6591234567", "123456");
    }

    @Test
    void aBlankAccountOrCode_isRefusedBeforeTheService() {
        for (Map<String, String> body : new Map[] {
                Map.of("identifier", "", "verificationCode", "123456"),
                Map.of("identifier", "+6591234567", "verificationCode", " "),
                Map.of("identifier", "+6591234567", "code", "123456")}) {
            CheckResetCodeDTO dto = JsonUtils.stringToObject(JsonUtils.objectToString(body), CheckResetCodeDTO.class);
            Set<ConstraintViolation<CheckResetCodeDTO>> violations = validator.validate(dto);
            assertThat(violations).as("body %s", body).isNotEmpty();
        }
    }
}

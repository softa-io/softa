package io.softa.starter.flow.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.handler.RequestInfoHandler;
import io.softa.framework.web.handler.WebExceptionHandler;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * An approval action the rules refuse answers with the reason, not a bare "System exception". softa-web's advice
 * registers before flow-starter's in an application, and its {@code Throwable} catch-all took every flow refusal
 * while flow-starter's advice had no order: Spring takes the first advice that handles an exception at all.
 */
class FlowExceptionHandlerOrderTest {

    @RestController
    static class RefusingController {

        @PostMapping("/refuse")
        void refuse() {
            throw new FlowActionValidationException("Nobody to CC: everyone named was already copied at this step");
        }

        @PostMapping("/deny")
        void deny() {
            throw new FlowAuthorizationException("User may not view flow instance i1");
        }

        @PostMapping("/fail")
        void fail() {
            throw new FlowRuntimeException("Instance i1 lost its pending approvals");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WebExceptionHandler web = new WebExceptionHandler();
        ReflectionTestUtils.setField(web, "messageHandler", mock(RequestInfoHandler.class));
        // In the order an application registers them: softa-web's first.
        mockMvc = MockMvcBuilders.standaloneSetup(new RefusingController())
                .setControllerAdvice(web, new FlowExceptionHandler())
                .build();
    }

    @Test
    void aRefusalIsABadRequestWithItsReason() throws Exception {
        mockMvc.perform(post("/refuse"))
                .andExpect(jsonPath("$.code").value(ResponseCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.error").value(containsString("Nobody to CC")));
    }

    @Test
    void anAuthorizationRefusalIsPermissionDenied() throws Exception {
        mockMvc.perform(post("/deny"))
                .andExpect(jsonPath("$.code").value(ResponseCode.PERMISSION_DENIED.getCode()));
    }

    @Test
    void anUnexpectedFlowErrorIsAnsweredLikeAnyOtherAndKeepsItsMessageOut() throws Exception {
        mockMvc.perform(post("/fail"))
                .andExpect(jsonPath("$.code").value(ResponseCode.ERROR.getCode()))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}

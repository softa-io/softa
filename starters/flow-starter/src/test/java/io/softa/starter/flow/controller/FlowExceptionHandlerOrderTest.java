package io.softa.starter.flow.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
            throw new FlowAuthorizationException("You do not have access to this request.");
        }

        @PostMapping("/fail")
        void fail() {
            throw new FlowRuntimeException("Instance i1 lost its pending approvals");
        }
    }

    /** What {@link RequestInfoHandler} adds to a log line: the request, and the ids it carried. */
    private static final String REQUEST = " Request: http://localhost/flow/instances/i1/urge ; User: Amy ; TraceID: t1";

    private final Logger logger = (Logger) LoggerFactory.getLogger(FlowExceptionHandler.class);

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WebExceptionHandler web = new WebExceptionHandler();
        ReflectionTestUtils.setField(web, "messageHandler", mock(RequestInfoHandler.class));
        RequestInfoHandler requestInfo = mock(RequestInfoHandler.class);
        when(requestInfo.getRequestInfo()).thenReturn(REQUEST);
        // In the order an application registers them: softa-web's first.
        mockMvc = MockMvcBuilders.standaloneSetup(new RefusingController())
                .setControllerAdvice(web, new FlowExceptionHandler(requestInfo))
                .build();
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
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

    /** The answer names no id; the log line carries the request, where whoever looks into a refusal finds them. */
    @Test
    void aRefusalIsLoggedWithTheRequestThatMetIt() throws Exception {
        mockMvc.perform(post("/refuse"));
        mockMvc.perform(post("/deny"));

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "FlowActionValidationException: Nobody to CC: everyone named was already copied at this step" + REQUEST,
                "FlowAuthorizationException: You do not have access to this request." + REQUEST);
    }

    @Test
    void anUnexpectedFlowErrorIsAnsweredLikeAnyOtherAndKeepsItsMessageOut() throws Exception {
        mockMvc.perform(post("/fail"))
                .andExpect(jsonPath("$.code").value(ResponseCode.ERROR.getCode()))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}

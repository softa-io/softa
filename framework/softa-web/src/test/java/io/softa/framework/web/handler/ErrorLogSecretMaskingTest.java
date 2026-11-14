package io.softa.framework.web.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.BaseException;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.web.aspect.ApiExceptionAspect;
import io.softa.framework.web.wrapper.CachedBodyHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A request refused for its password, code or ticket is logged without them — the line Sentry takes as an event
 * at ERROR and as a breadcrumb at WARN — in each place the line repeats the request: the method parameters, the
 * query string and the cached request body.
 */
class ErrorLogSecretMaskingTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(WebExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private SystemConfig previousEnv;

    @BeforeEach
    void setUp() {
        previousEnv = SystemConfig.env;
        // enableLogRequestBody stays at its default, true: the cached body is part of the line.
        SystemConfig.env = new SystemConfig();
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
        SystemConfig.env = previousEnv;
    }

    /** A sign-in refused for its password: the WARN line still says who signed in, to which tenant. */
    @Test
    void refusedSignInKeepsThePasswordOut() throws IOException {
        ILoggingEvent line = refuse(post("/login/loginByPassword",
                        "{\"identifier\":\"amy@example.com\",\"password\":\"Pa55-word!\",\"tenantCode\":\"acme\"}"),
                endpoints -> endpoints.loginByPassword(new PasswordLogin("amy@example.com", "Pa55-word!", "acme")));

        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getFormattedMessage())
                .doesNotContain("Pa55-word!")
                .contains("amy@example.com", "acme", "\"password\":\"***\"");
    }

    /** A password change that fails unexpectedly is an ERROR line, a Sentry event: no ticket, no new password. */
    @Test
    void failedPasswordChangeKeepsTicketAndPasswordOut() throws IOException {
        ILoggingEvent line = refuse(post("/me/password/change", "{\"ticket\":\"tkt-7f3a\",\"newPassword\":\"N3w-pass!\"}"),
                endpoints -> endpoints.changePassword(new ChangePassword("tkt-7f3a", "N3w-pass!")));

        assertThat(line.getLevel()).isEqualTo(Level.ERROR);
        assertThat(line.getFormattedMessage())
                .doesNotContain("tkt-7f3a", "N3w-pass!")
                .contains("\"ticket\":\"***\"", "\"newPassword\":\"***\"");
    }

    /** Link tokens sent in the query string are masked there and as method parameters; the document id is kept. */
    @Test
    void refusedLinkKeepsItsTokensOut() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sign/context");
        request.setQueryString("token=lnk-123&verificationToken=vt-456&documentId=42");
        ILoggingEvent line = refuse(request, endpoints -> endpoints.signingContext("lnk-123", "vt-456", 42L));

        assertThat(line.getFormattedMessage())
                .doesNotContain("lnk-123", "vt-456")
                .contains("?token=***&verificationToken=***&documentId=42", "token = ***", "documentId = 42");
    }

    /**
     * Runs a request through the parts that write its error log line — {@link ApiExceptionAspect} around the
     * endpoint, then {@link WebExceptionHandler} with {@link RequestInfoHandler} — and returns that line. A request
     * with a JSON body is wrapped to cache it, as {@code RequestBodyCacheFilter} does.
     */
    private ILoggingEvent refuse(MockHttpServletRequest servletRequest, Consumer<CredentialEndpoints> call)
            throws IOException {
        HttpServletRequest request = servletRequest.getContentLength() > 0
                ? new CachedBodyHttpServletRequest(servletRequest)
                : servletRequest;
        ApiExceptionAspect aspect = new ApiExceptionAspect();
        ReflectionTestUtils.setField(aspect, "request", request);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new CredentialEndpoints());
        proxyFactory.addAspect(aspect);
        CredentialEndpoints endpoints = proxyFactory.getProxy();

        Throwable refusal = catchThrowable(() -> call.accept(endpoints));

        RequestInfoHandler requestInfo = new RequestInfoHandler();
        ReflectionTestUtils.setField(requestInfo, "request", request);
        WebExceptionHandler exceptionHandler = new WebExceptionHandler();
        ReflectionTestUtils.setField(exceptionHandler, "messageHandler", requestInfo);
        if (refusal instanceof BaseException baseException) {
            exceptionHandler.handleException(baseException);
        } else {
            exceptionHandler.handleException((Exception) refusal);
        }
        assertThat(logged.list).hasSize(1);
        return logged.list.getFirst();
    }

    private static MockHttpServletRequest post(String uri, String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** Endpoints shaped like the sign-in, {@code /me} and signing-link ones, each refusing its request. */
    @RestController
    static class CredentialEndpoints {

        @PostMapping("/login/loginByPassword")
        public void loginByPassword(@RequestBody PasswordLogin body) {
            throw new BusinessException("The account or password is incorrect.");
        }

        @PostMapping("/me/password/change")
        public void changePassword(@RequestBody ChangePassword body) {
            throw new IllegalStateException("The password store is unavailable.");
        }

        @GetMapping("/sign/context")
        public void signingContext(@RequestParam String token, @RequestParam String verificationToken,
                                   @RequestParam Long documentId) {
            throw new BusinessException("The signing link has expired.");
        }
    }

    record PasswordLogin(String identifier, String password, String tenantCode) {
    }

    record ChangePassword(String ticket, String newPassword) {
    }
}

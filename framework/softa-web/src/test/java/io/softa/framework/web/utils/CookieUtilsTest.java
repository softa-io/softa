package io.softa.framework.web.utils;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session cookie stays off requests another site starts, and page scripts cannot read it.
 */
class CookieUtilsTest {

    @Test
    void sessionCookieIsLaxHttpOnlyAndSecure() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        CookieUtils.setCookie(response, "sessionId", "abc");

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("sessionId=abc")
                .contains("SameSite=Lax")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("Path=/");
    }
}

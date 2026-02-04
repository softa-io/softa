package io.softa.framework.web.utils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Cookie Utils
 */
@Slf4j
public class CookieUtils {

    private CookieUtils() {}

    /** The expiration date of the cookie, default is 30 days */
    private static final int DEFAULT_COOKIE_AGE = 60 * 60 * 24 * 30;

    /**
     * Get the value of the specified cookie by name.
     *
     * @param request The HttpServletRequest object
     * @param name The name of the cookie
     * @return The value of the cookie
     */
    public static String getCookie(HttpServletRequest request, String name) {
        String value = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie: cookies) {
                if (cookie.getName().equals(name)) {
                    try {
                        value = URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        log.error("An error occurred while retrieving the cookie value: ", e);
                    }
                }
            }
        }
        return value;
    }

    /**
     * Set a cookie with the default cookie age.
     *
     * @param response The HttpServletResponse object
     * @param name The name of the cookie
     * @param value The value of the cookie
     */
    public static void setCookie(HttpServletResponse response, String name, String value) {
        setCookie(response, name, value, DEFAULT_COOKIE_AGE);
    }

    /**
     * Set a cookie with a specified cookie age.
     *
     * @param response The HttpServletResponse object
     * @param name The name of the cookie
     * @param value The value of the cookie
     * @param maxAge The maximum age of the cookie
     */
    public static void setCookie(HttpServletResponse response, String name, String value, int maxAge) {
        String encodedValue = value != null ? URLEncoder.encode(value, StandardCharsets.UTF_8) : "";
        ResponseCookie cookie = ResponseCookie.from(name, encodedValue)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * Clear the specified cookie
     * @param response The HttpServletResponse object
     * @param name The name of the cookie
     */
    public static void clearCookie(HttpServletResponse response, String name) {
        setCookie(response, name, null, 0);
    }
}

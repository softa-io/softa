package io.softa.starter.user.service.impl;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.softa.starter.user.util.ClientInfo;

/** Binds the request a test runs under: the APP (its platform header set) or Web, from an address. */
final class ClientRequests {

    private ClientRequests() {
    }

    static void fromApp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientInfo.PLATFORM_HEADER, "ios");
        request.addHeader(ClientInfo.APP_VERSION_HEADER, "1.0.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    static void fromAddress(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    static void clear() {
        RequestContextHolder.resetRequestAttributes();
    }
}

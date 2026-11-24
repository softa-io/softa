package io.softa.starter.message.sms.support;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import io.softa.framework.web.resilience.TransientHttpException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectFailuresTest {

    /** The name Apache HttpClient 5 and Netty give their connect timeout. */
    static class ConnectTimeoutException extends IOException {
        ConnectTimeoutException(String message) {
            super(message);
        }
    }

    @Test
    void neverReachingTheProviderIsAConnectFailure() {
        assertTrue(ConnectFailures.isConnectFailure(new ConnectException("Connection refused")));
        assertTrue(ConnectFailures.isConnectFailure(new UnknownHostException("gatewayv5.onewaysms.sg")));
        assertTrue(ConnectFailures.isConnectFailure(new HttpConnectTimeoutException("HTTP connect timed out")));
        assertTrue(ConnectFailures.isConnectFailure(new ConnectTimeoutException("Connect to host timed out")));
        assertTrue(ConnectFailures.isConnectFailure(new SocketTimeoutException("Connect timed out")));
    }

    @Test
    void theCauseIsReadThroughSpringsWrapper() {
        assertTrue(ConnectFailures.isConnectFailure(new ResourceAccessException("I/O error on GET request",
                new ConnectException("Connection refused"))));
    }

    @Test
    void aFailureAfterConnectingIsNot() {
        assertFalse(ConnectFailures.isConnectFailure(new SocketTimeoutException("Read timed out")));
        assertFalse(ConnectFailures.isConnectFailure(new HttpTimeoutException("request timed out")));
        assertFalse(ConnectFailures.isConnectFailure(new SocketException("Connection reset")));
        assertFalse(ConnectFailures.isConnectFailure(new ResourceAccessException("I/O error on GET request",
                new SocketTimeoutException("Read timed out"))));
        assertFalse(ConnectFailures.isConnectFailure(new TransientHttpException(503, "Service Unavailable",
                java.net.URI.create("https://example.com"))));
        assertFalse(ConnectFailures.isConnectFailure(null));
    }
}

package io.softa.starter.message.sms.support;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Locale;

/**
 * Whether a failed call never reached the provider: the host was not found, the connection was refused,
 * or connecting timed out. Such a message was certainly not sent, so sending it again cannot deliver it
 * twice. Every other failure without an answer (a read timeout, a connection reset halfway, a response
 * that could not be read) may come after the provider has already sent it.
 * <p>
 * The exception depends on the HTTP client Spring picked, so the whole cause chain is read: the JDK's
 * {@link ConnectException} (Apache's {@code HttpHostConnectException} extends it),
 * {@link HttpConnectTimeoutException} for the JDK client, a {@code ConnectTimeoutException} by name for
 * Apache and Netty, and the {@code "Connect timed out"} {@link SocketTimeoutException} of
 * {@code HttpURLConnection}.
 */
public final class ConnectFailures {

    private static final int MAX_DEPTH = 10;

    private ConnectFailures() {
    }

    public static boolean isConnectFailure(Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++) {
            if (t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException
                    || t instanceof HttpConnectTimeoutException
                    || "ConnectTimeoutException".equals(t.getClass().getSimpleName())) {
                return true;
            }
            if (t instanceof SocketTimeoutException && t.getMessage() != null
                    && t.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return false;
    }
}

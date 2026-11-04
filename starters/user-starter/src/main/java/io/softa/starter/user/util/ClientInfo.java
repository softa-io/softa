package io.softa.starter.user.util;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is calling, as far as the request says: the client's address and, for the APP, its platform,
 * version and device model. Recorded on sessions, login history and the security log.
 *
 * <p>{@code X-App-Platform} ({@code ios} / {@code android}) and {@code X-App-Version} are the APP's
 * standard headers; {@code X-Device-Model} is optional. Web and H5 send none of them, so all three
 * are null there.
 *
 * @param ip          the client address — the {@code X-Forwarded-For} entry the trusted proxies
 *                    put there, else {@code X-Real-IP} when configured as trusted, else the socket
 *                    peer
 * @param userAgent   the {@code User-Agent} header
 * @param platform    {@code X-App-Platform}, lower-cased
 * @param appVersion  {@code X-App-Version}
 * @param deviceModel {@code X-Device-Model}
 */
public record ClientInfo(String ip, String userAgent, String platform, String appVersion, String deviceModel) {

    public static final String PLATFORM_HEADER = "X-App-Platform";
    public static final String APP_VERSION_HEADER = "X-App-Version";
    public static final String DEVICE_MODEL_HEADER = "X-Device-Model";

    private static final ClientInfo NONE = new ClientInfo(null, null, null, null, null);

    /** Proxies that append to {@code X-Forwarded-For}; set from configuration at startup. */
    private static volatile int trustedProxies = 1;

    /** Whether a proxy in front of the application sets {@code X-Real-IP}; set from configuration. */
    private static volatile boolean trustRealIp = false;

    /** How many proxies in front of the application append to {@code X-Forwarded-For} (at least one). */
    public static void trustProxies(int count) {
        trustedProxies = Math.max(1, count);
    }

    /**
     * Whether to read {@code X-Real-IP} when {@code X-Forwarded-For} is absent. Off unless a proxy in
     * front is known to set it: otherwise it is a header the caller wrote, and any address it names
     * would pass the per-address limits.
     */
    public static void trustRealIp(boolean trust) {
        trustRealIp = trust;
    }

    /** The calling client, or all-null outside an HTTP request (a scheduled job, a test). */
    public static ClientInfo current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return NONE;
        }
        return of(attributes.getRequest());
    }

    public static ClientInfo of(HttpServletRequest request) {
        if (request == null) {
            return NONE;
        }
        String platform = StringUtils.trimToNull(request.getHeader(PLATFORM_HEADER));
        return new ClientInfo(clientIp(request),
                StringUtils.abbreviate(StringUtils.trimToNull(request.getHeader("User-Agent")), 255),
                platform == null ? null : platform.toLowerCase(),
                StringUtils.trimToNull(request.getHeader(APP_VERSION_HEADER)),
                StringUtils.trimToNull(request.getHeader(DEVICE_MODEL_HEADER)));
    }

    /** Whether the call came from the APP rather than Web / H5: it names its platform or its version. */
    public boolean isApp() {
        return platform != null || appVersion != null;
    }

    /** One line naming the device, for the security log: "iPhone15,2 (ios 1.2.0)", or null for Web. */
    public String deviceLabel() {
        if (!isApp()) {
            return null;
        }
        String model = deviceModel == null ? "" : deviceModel + " ";
        return model + "(" + platform + (appVersion == null ? "" : " " + appVersion) + ")";
    }

    /**
     * The client address as the outermost of our own proxies saw it: counting the trusted proxies
     * from the right of {@code X-Forwarded-For}. Each of them appends the address it received from,
     * so with one proxy that is the last entry, with two the one before it. Every entry further left
     * was written by whoever sent the request and can say anything; trusting the first one let a
     * caller rotate a forged address past the per-address limits.
     */
    private static String clientIp(HttpServletRequest request) {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(clientIp)) {
            String[] hops = clientIp.split(",");
            return hops[Math.max(0, hops.length - trustedProxies)].trim();
        }
        clientIp = trustRealIp ? request.getHeader("X-Real-IP") : null;
        if (StringUtils.isNotBlank(clientIp)) {
            return clientIp.trim();
        }
        return StringUtils.trimToNull(request.getRemoteAddr());
    }
}

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
 * @param ip          the client address — last hop of {@code X-Forwarded-For}, else {@code X-Real-IP},
 *                    else the socket peer
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
     * The client address as the proxy in front of us saw it: the LAST hop of {@code X-Forwarded-For}.
     * Every earlier entry was written by whoever sent the request and can say anything; the last one
     * is the one our own proxy appended (a proxy that replaces the header instead leaves only that
     * one). Trusting the first hop let a caller rotate a forged address past the per-address limits.
     */
    private static String clientIp(HttpServletRequest request) {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(clientIp)) {
            String[] hops = clientIp.split(",");
            return hops[hops.length - 1].trim();
        }
        clientIp = request.getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(clientIp)) {
            return clientIp.trim();
        }
        return StringUtils.trimToNull(request.getRemoteAddr());
    }
}

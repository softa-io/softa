package io.softa.starter.user.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import io.softa.starter.user.util.ClientInfo;

/**
 * Per-address limits on signing in, and how the caller's address is read (prefix
 * {@code softa.user.login-protection}).
 *
 * <pre>
 * softa:
 *   user:
 *     login-protection:
 *       trusted-proxies: 1
 *       trust-real-ip: false
 *       attempts-per-address-per-hour: 300
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "softa.user.login-protection")
public class LoginProtectionProperties {

    /**
     * How many proxies in front of the application append to {@code X-Forwarded-For}. The caller's
     * address is the entry that many places from the right: everything left of it was written by
     * the caller and can say anything. One for a single reverse proxy (or a proxy that replaces the
     * header); two behind a load balancer and a reverse proxy that both append. Check it against the
     * deployment: too low reads a proxy's own address and puts every caller in one bucket, too high
     * reads an entry the caller wrote.
     */
    private int trustedProxies = 1;

    /**
     * Read {@code X-Real-IP} when a request carries no {@code X-Forwarded-For}. Off by default: unless
     * the proxy in front sets it, the caller does. The gateway is expected to append to
     * {@code X-Forwarded-For}; turn this on only behind one that sets {@code X-Real-IP} instead.
     */
    private boolean trustRealIp;

    /**
     * Codes sent, codes checked and passwords tried from one address in an hour, all together.
     * Generous on purpose: an office behind one address signs everyone in through it.
     */
    private int attemptsPerAddressPerHour = 300;

    @PostConstruct
    void apply() {
        ClientInfo.trustProxies(trustedProxies);
        ClientInfo.trustRealIp(trustRealIp);
    }
}

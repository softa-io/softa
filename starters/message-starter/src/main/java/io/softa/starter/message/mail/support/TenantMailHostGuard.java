package io.softa.starter.message.mail.support;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Keeps a tenant's own mail servers on the internet: a host that is, or resolves to, an address
 * inside the deployment — loopback, link-local (the cloud metadata endpoint included), private
 * ranges, carrier-grade NAT, the unspecified address, multicast — is refused. Without it a tenant
 * could aim the application's SMTP / IMAP / POP3 client at internal services and read their banners
 * and errors back through the connection test.
 * <p>
 * Checked on save and again before every connection, so a name that later resolves inward is caught
 * too. A host that does not resolve is not refused by either: the connection attempt fails on it like
 * on any other connection error, and the delivery retries — a DNS outage must not fail mail for good.
 * Only with multi-tenancy enabled and
 * {@code softa.message.mail.block-private-tenant-hosts} on (the default); the platform's own servers
 * are never checked.
 */
@Component
public class TenantMailHostGuard {

    private final MessageProperties properties;

    public TenantMailHostGuard(MessageProperties properties) {
        this.properties = properties;
    }

    /** Whether tenants' hosts are being checked in this deployment. */
    public boolean active() {
        return TenantScopes.multiTenancyEnabled() && properties.getMail().isBlockPrivateTenantHosts();
    }

    /**
     * The save-time check: the reason {@code host} is refused, or {@code null} when it may be saved —
     * a host that does not resolve yet included.
     */
    public String refusalOnSave(String host) {
        if (!active() || !StringUtils.hasText(host)) {
            return null;
        }
        try {
            return refusal(host.trim(), InetAddress.getAllByName(host.trim()));
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * The connect-time check: throws when {@code host} resolves to an address inside the deployment. A
     * host that does not resolve passes — the connection itself then fails and is retried.
     */
    public void requirePublicBeforeConnecting(String host) {
        if (!active() || !StringUtils.hasText(host)) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host.trim());
        } catch (UnknownHostException e) {
            return;
        }
        String refusal = refusal(host.trim(), addresses);
        if (refusal != null) {
            throw new BusinessException(refusal);
        }
    }

    private static String refusal(String host, InetAddress[] addresses) {
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                return "The mail server host " + host + " resolves to " + address.getHostAddress()
                        + ", an address inside the deployment. A tenant's mail server must be reachable "
                        + "on the internet.";
            }
        }
        return null;
    }

    /** Package-private so the address classes can be pinned without DNS. */
    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 0.0.0.0/8 (this network), 100.64.0.0/10 (carrier-grade NAT), 192.0.0.0/24 (IETF protocol
            // assignments), 198.18.0.0/15 (benchmarking), 240.0.0.0/4 (reserved) and the broadcast address.
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 240;
        }
        if (address instanceof Inet6Address) {
            // fc00::/7 unique local; IPv4-compatible / -mapped forms carry an IPv4 address to check.
            if ((b[0] & 0xfe) == 0xfc) {
                return true;
            }
            boolean v4Embedded = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    v4Embedded = false;
                    break;
                }
            }
            if (v4Embedded && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xff && b[11] == (byte) 0xff))) {
                try {
                    return isInternal(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}

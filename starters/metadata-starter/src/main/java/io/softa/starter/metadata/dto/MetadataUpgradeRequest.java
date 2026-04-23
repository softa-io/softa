package io.softa.starter.metadata.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envelope for the {@code /metadata/upgrade} endpoint.
 * <p>
 * The runtime validates the envelope synchronously, stashes the callback coordinates,
 * returns 202 immediately, and applies the packages on a background virtual thread.
 * When the background work finishes it POSTs a {@link MetadataUpgradeCallback} to
 * {@code callbackUrl} with {@code callbackToken} attached in the
 * {@code X-Softa-Callback-Token} header. The caller verifies both the URL and the token
 * before accepting the callback as authoritative.
 */
@Data
@NoArgsConstructor
public class MetadataUpgradeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Metadata packages to apply. */
    private List<MetadataUpgradePackage> packages;

    /**
     * Absolute URL the runtime must POST the completion callback to.
     * The host/path is caller-defined — the runtime does not infer it from its own
     * configuration — so that studio → runtime reachability and runtime → studio
     * reachability can be asymmetric (e.g. outbound proxy, public ingress).
     */
    private String callbackUrl;

    /**
     * Opaque one-time token. Echoed back in the callback {@code X-Softa-Callback-Token}
     * header so the caller can match the callback to the original deployment and
     * reject replays / forged callbacks.
     */
    private String callbackToken;
}

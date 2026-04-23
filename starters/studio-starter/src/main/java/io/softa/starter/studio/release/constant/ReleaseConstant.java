package io.softa.starter.studio.release.constant;

import java.time.Duration;

/**
 * HTTP-level configuration for the studio → runtime RestClient
 */
public interface ReleaseConstant {

    /** Server-relative callback path — single source of truth for runtime → studio webhooks. */
    String CALLBACK_PATH = "/DesignDeployment/callback";

    /**
     * How long a newly-minted callback token is valid for. A callback arriving after
     * the expiry is rejected even if the token matches — bounds the exposure window
     * of a token that leaked post-send.
     */
    Duration CALLBACK_TOKEN_TTL = Duration.ofHours(1);

    /** TCP connect timeout. Short — any connect taking longer is almost certainly a bad endpoint. */
    Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Socket read timeout. Covers the slowest single call (metadata export / upgrade). */
    Duration READ_TIMEOUT = Duration.ofSeconds(60);
}

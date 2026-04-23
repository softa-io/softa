package io.softa.starter.metadata.upgrade;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import io.softa.framework.web.signature.SignatureConstant;
import io.softa.starter.metadata.dto.MetadataUpgradeCallback;

/**
 * Posts the {@link MetadataUpgradeCallback} back to the studio's webhook URL once
 * the async upgrade finishes.
 * <p>
 * The callback is not signed with Ed25519 — authentication is the one-time
 * {@code callbackToken} that the studio generated and embedded in the originating
 * request. The token is echoed in {@link SignatureConstant#CALLBACK_TOKEN} and
 * burned on first receipt, so an attacker without access to the original
 * (signed) upgrade request cannot forge a callback.
 * <p>
 * Retry / circuit breaker policies are owned by the {@code metadataCallbackRestClient}
 * bean; we intentionally swallow {@link RestClientException} and log instead of
 * rethrowing because the originating upgrade has already applied — rolling back
 * the in-memory metadata because the studio is briefly unreachable would be
 * worse than a stale deployment record on the studio side.
 */
@Slf4j
@Component
public class MetadataCallbackClient {

    private final RestClient restClient;

    public MetadataCallbackClient(@Qualifier("metadataCallbackRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fire-and-log callback. The caller treats delivery as best-effort.
     *
     * @param callbackUrl   absolute studio webhook URL from the originating request
     * @param callbackToken one-time token echoed in the auth header
     * @param payload       completion payload (status + optional error + duration)
     */
    public void sendCallback(String callbackUrl, String callbackToken, MetadataUpgradeCallback payload) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            log.warn("Skipping upgrade callback — no callback URL supplied by caller.");
            return;
        }
        try {
            restClient.post()
                    .uri(URI.create(callbackUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SignatureConstant.CALLBACK_TOKEN, callbackToken)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // The upgrade itself already succeeded/failed on this side — the studio's
            // deployment record will stay in DEPLOYING until an operator retries or
            // the studio's own reconciliation logic picks it up.
            log.warn("Failed to deliver upgrade callback to {}: {}", callbackUrl, e.getMessage(), e);
        }
    }
}

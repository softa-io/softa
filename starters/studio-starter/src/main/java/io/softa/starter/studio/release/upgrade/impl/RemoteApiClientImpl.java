package io.softa.starter.studio.release.upgrade.impl;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.URLUtils;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.metadata.dto.MetadataUpgradePackage;
import io.softa.starter.metadata.dto.MetadataUpgradeRequest;
import io.softa.starter.studio.release.config.StudioRemoteClientConfig;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.signing.DesignAppEnvSigningInterceptor;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;

/**
 * Remote API client for studio → runtime calls (metadata upgrade / export).
 * <p>
 * Resilience (timeouts, retry, circuit breaker, metrics) and Ed25519 signing are
 * supplied entirely by the {@code studioRemoteRestClient} bean — see
 * {@link StudioRemoteClientConfig}.
 * This class only owns the business contract: URL construction, env-id hint for
 * the signing interceptor, idempotency key, and {@link ApiResponse} unwrapping.
 * <p>
 * Authentication is the per-env Ed25519 keypair. We tag each request with the
 * {@link DesignAppEnvSigningInterceptor#ENV_ID_HEADER} header so the scenario
 * interceptor knows which env's key to pick up; the interceptor strips the
 * marker and adds the three wire headers before the request leaves the process.
 * <p>
 * Every call attaches an {@code Idempotency-Key} header so the runtime side can
 * dedupe replays of the same logical call — this is the contract that makes
 * upgrade retries safe when the first attempt timed out after the server already
 * applied the change.
 */
@Slf4j
@Service
public class RemoteApiClientImpl implements RemoteApiClient {

    /**
     * HTTP header carrying the idempotency key. The runtime side is expected to
     * cache the first call's response and replay it for subsequent calls with the
     * same key, within a reasonable dedup window.
     */
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * Statuses accepted from the runtime for a dispatched upgrade. 202 is the
     * expected response (envelope accepted, work queued); 200 is tolerated so a
     * future same-thread runtime would not break the contract.
     */
    private static final Set<HttpStatus> DISPATCH_ACCEPTED_STATUSES =
            Set.of(HttpStatus.ACCEPTED, HttpStatus.OK);

    private static final ParameterizedTypeReference<ApiResponse<Object>> API_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    @Autowired
    public RemoteApiClientImpl(@Qualifier("studioRemoteRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void remoteUpgrade(DesignAppEnv appEnv,
                              List<MetadataUpgradePackage> modelPackages,
                              String callbackUrl,
                              String callbackToken) {
        MetadataUpgradeRequest envelope = new MetadataUpgradeRequest();
        envelope.setPackages(modelPackages);
        envelope.setCallbackUrl(callbackUrl);
        envelope.setCallbackToken(callbackToken);

        URI uri = URI.create(URLUtils.buildUrl(appEnv.getUpgradeEndpoint(), MetadataConstant.METADATA_UPGRADE_API));
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<ApiResponse<Object>> responseEntity = restClient.post()
                .uri(uri)
                .headers(headers -> populateHeaders(headers, appEnv.getId(), idempotencyKey))
                .body(envelope)
                .retrieve()
                .toEntity(API_RESPONSE_TYPE);

        HttpStatusCode status = responseEntity.getStatusCode();
        ApiResponse<Object> apiResponse = responseEntity.getBody();
        Assert.isTrue(DISPATCH_ACCEPTED_STATUSES.contains(HttpStatus.resolve(status.value())),
                "Remote upgrade dispatch returned unexpected status: URI={0}, status={1}, body={2}",
                uri, status, apiResponse);
        // 202 Accepted bodies are advisory — the runtime may echo a queued receipt or
        // send nothing at all. We only fail if a non-success code was wrapped.
        Assert.isTrue(apiResponse == null || ResponseCode.SUCCESS.getCode().equals(apiResponse.getCode()),
                "Remote upgrade dispatch rejected: URI={0}, response={1}", uri, apiResponse);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchRuntimeMetadata(DesignAppEnv appEnv, String runtimeModelName) {
        Assert.notNull(appEnv.getAppId(), "DesignAppEnv {0} is missing appId — runtime export would fetch other apps' rows.",
                appEnv.getId());
        String baseUrl = URLUtils.buildUrl(appEnv.getUpgradeEndpoint(), MetadataConstant.METADATA_EXPORT_API);
        // UriComponentsBuilder handles percent-encoding; raw concatenation breaks on
        // names containing `&`, `=`, `%` or non-ASCII characters.
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("modelName", runtimeModelName)
                .queryParam("appId", appEnv.getAppId())
                .build()
                .toUri();
        Object data = callRemoteApi(appEnv, uri, null);
        return data == null ? List.of() : (List<Map<String, Object>>) data;
    }

    /**
     * POST to {@code uri} and return the unwrapped {@link ApiResponse#getData()}.
     * Returns {@code null} for an empty success body.
     * <p>
     * Retry, backoff, circuit breaking and request signing all happen inside the
     * {@link RestClient} interceptor chain; this method only deals with the
     * request/response envelope.
     */
    private Object callRemoteApi(DesignAppEnv appEnv, URI uri, Object body) {
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<ApiResponse<Object>> responseEntity = restClient.post()
                .uri(uri)
                .headers(headers -> populateHeaders(headers, appEnv.getId(), idempotencyKey))
                .body(body == null ? "" : body)
                .retrieve()
                .toEntity(API_RESPONSE_TYPE);

        HttpStatusCode status = responseEntity.getStatusCode();
        ApiResponse<Object> apiResponse = responseEntity.getBody();
        Assert.isTrue(HttpStatus.OK.equals(status),
                "Remote API returned non-200 status: URI={0}, status={1}, body={2}",
                uri, status, apiResponse);
        Assert.notNull(apiResponse,
                "Remote API returned empty body: URI={0}, status={1}", uri, status);
        Assert.isTrue(ResponseCode.SUCCESS.getCode().equals(apiResponse.getCode()),
                "Remote API call failed: URI={0}, response={1}", uri, apiResponse);
        return apiResponse.getData();
    }

    private void populateHeaders(HttpHeaders headers, Long envId, String idempotencyKey) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(DesignAppEnvSigningInterceptor.ENV_ID_HEADER, Long.toString(envId));
        headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
    }
}

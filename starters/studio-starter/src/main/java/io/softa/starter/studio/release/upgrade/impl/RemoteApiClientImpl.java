package io.softa.starter.studio.release.upgrade.impl;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.URLUtils;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.metadata.constant.MetadataConstant;
import io.softa.starter.metadata.dto.MetadataChangeSet;
import io.softa.starter.metadata.dto.RuntimeChecksumsDTO;
import io.softa.starter.metadata.dto.RuntimeExportFilter;
import io.softa.starter.studio.release.config.StudioRemoteClientConfig;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.signing.DesignAppEnvSigningInterceptor;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;

/**
 * Remote API client for studio → runtime calls (read schema + checksums, apply changes).
 * <p>
 * Resilience (timeouts, retry, circuit breaker, metrics) and Ed25519 signing are supplied entirely by
 * the {@code studioRemoteRestClient} bean — see {@link StudioRemoteClientConfig}. This class owns only
 * the business contract: URL construction, env-id hint for the signing interceptor, idempotency key,
 * and {@link ApiResponse} unwrapping. Every call attaches an {@code Idempotency-Key} so the runtime can
 * dedupe replays (safe retries after a timeout that the server already applied).
 */
@Slf4j
@Service
public class RemoteApiClientImpl implements RemoteApiClient {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final ParameterizedTypeReference<ApiResponse<Object>> API_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public RemoteApiClientImpl(@Qualifier("studioRemoteRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<Map<String, Object>> fetchRuntimeMetadata(DesignAppEnv appEnv, String appCode, String runtimeModelName) {
        // Full export — no aggregate-key narrowing (a null filter ⇒ full app-scoped catalog).
        return exportRuntimeMetadata(appEnv, appCode, runtimeModelName, new RuntimeExportFilter(null, null));
    }

    @Override
    public List<Map<String, Object>> fetchRuntimeMetadata(DesignAppEnv appEnv, String appCode, String runtimeModelName,
                                                          String keyColumn, Collection<String> keyValues) {
        // Narrowed export — only the requested aggregate business keys.
        return exportRuntimeMetadata(appEnv, appCode, runtimeModelName,
                new RuntimeExportFilter(keyColumn, List.copyOf(keyValues)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exportRuntimeMetadata(DesignAppEnv appEnv, String appCode,
                                                            String runtimeModelName, RuntimeExportFilter filter) {
        Assert.notBlank(appCode, "appCode is required — the runtime rejects exports without an identity handshake.");
        String baseUrl = URLUtils.buildUrl(appEnv.getUpgradeEndpoint(), MetadataConstant.METADATA_EXPORT_API);
        // UriComponentsBuilder handles percent-encoding; raw concatenation breaks on
        // names containing `&`, `=`, `%` or non-ASCII characters. The aggregate-key narrowing rides in
        // the body (not the URL), so a large key set never bumps the URL-length limit.
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("modelName", runtimeModelName)
                .queryParam("appCode", appCode)
                .build()
                .toUri();
        Object data = callRemoteApi(appEnv, uri, filter);
        return data == null ? List.of() : (List<Map<String, Object>>) data;
    }

    @Override
    public RuntimeChecksumsDTO fetchRuntimeChecksums(DesignAppEnv appEnv, String appCode) {
        Assert.notBlank(appCode, "appCode is required — the runtime rejects exports without an identity handshake.");
        URI uri = UriComponentsBuilder
                .fromUriString(URLUtils.buildUrl(appEnv.getUpgradeEndpoint(), MetadataConstant.METADATA_CHECKSUMS_API))
                .queryParam("appCode", appCode)
                .build()
                .toUri();
        Object data = callRemoteApi(appEnv, uri, null);
        Assert.notNull(data, "Runtime checksums returned no data: URI={0}", uri);
        return JsonUtils.jsonNodeToObject(JsonUtils.objectToJsonNode(data), RuntimeChecksumsDTO.class);
    }

    @Override
    public void applyChanges(DesignAppEnv appEnv, String appCode, MetadataChangeSet changeSet) {
        Assert.notBlank(appCode, "appCode is required — the runtime rejects writes without an identity handshake.");
        Assert.notNull(changeSet, "Metadata change set must not be null.");
        URI uri = UriComponentsBuilder
                .fromUriString(URLUtils.buildUrl(appEnv.getUpgradeEndpoint(), MetadataConstant.METADATA_APPLY_DESIRED_API))
                .queryParam("appCode", appCode)
                .build()
                .toUri();
        callRemoteApi(appEnv, uri, changeSet);
    }

    /**
     * POST to {@code uri} and return the unwrapped {@link ApiResponse#getData()} (or {@code null} for
     * an empty success body). Retry / backoff / circuit breaking / signing all happen inside the
     * {@link RestClient} interceptor chain.
     */
    private Object callRemoteApi(DesignAppEnv appEnv, URI uri, Object body) {
        String idempotencyKey = UUID.randomUUID().toString();

        ResponseEntity<ApiResponse<Object>> responseEntity = restClient.post()
                .uri(uri)
                .headers(headers -> populateHeaders(headers, appEnv.getId(), idempotencyKey))
                .body(body == null ? "" : body)
                .retrieve()
                // Without this, retrieve() rethrows a 4xx/5xx as an opaque HttpClientErrorException with no
                // URI/body context — and the common failure mode (401 bad signature, 403 appCode mismatch)
                // would bypass the annotated asserts below. Surface the runtime's error body + target here.
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new IllegalArgumentException("Remote API call failed: URI={0}, status={1}, body={2}",
                            uri, response.getStatusCode(),
                            StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8));
                })
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

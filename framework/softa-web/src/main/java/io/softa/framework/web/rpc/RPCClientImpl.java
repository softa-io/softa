package io.softa.framework.web.rpc;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.ExternalException;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.base.utils.SerializeUtils;
import io.softa.framework.orm.config.RPCProperties;
import io.softa.framework.orm.constant.RPCConstant;
import io.softa.framework.orm.rpc.RemoteApiClient;
import io.softa.framework.web.resilience.ResilientRestClientBuilder;
import io.softa.framework.web.response.ApiResponseErrorDetails;

/**
 * Model-RPC client for internal service-to-service calls.
 * <p>
 * HTTP-level concerns (retry, circuit breaker, timeouts, metrics) are delegated to
 * the {@code rpcRestClient} bean built via
 * {@link ResilientRestClientBuilder}; YAML
 * policies live under {@code resilience4j.{retry,circuitbreaker}.instances.softa-rpc}.
 * This class owns only the RPC envelope: URL construction, signed headers, body
 * serialization, and {@link ApiResponseErrorDetails} unwrapping.
 */
@Slf4j
@Component
public class RPCClientImpl implements RemoteApiClient {

    @Autowired
    @Qualifier("rpcRestClient")
    private RestClient restClient;

    @Autowired
    private RPCProperties rpcProperties;

    private <T> T handleResponse(ApiResponseErrorDetails<T> response) {
        // Check if the response is success, which means the remote service is available
        if (!response.isSuccess()) {
            String errorMessage = String.format("RPC failed: Code: %d, Message: %s",
                    response.getCode(), response.getMessage());
            if (response.getError() != null) {
                errorMessage += ", Error: " + response.getError();
            }
            throw new ExternalException(errorMessage);
        }

        // Check if the response data is null, which means the remote service returns null
        if (response.getData() == null) {
            log.debug("RPC call success, but returns empty data");
            return null;
        }

        try {
            String serializedBase64 = (String) response.getData();
            Object resultData = SerializeUtils.deserialize(serializedBase64, Object.class);
            return Cast.of(resultData);
        } catch (Exception e) {
            throw new ExternalException("RPC response data deserialization failed: {}", e.getMessage(), e);
        }
    }

    private <T> T sendPostRequest(String url, HttpHeaders headers, String serializedBody) {
        ParameterizedTypeReference<ApiResponseErrorDetails<T>> responseType = new ParameterizedTypeReference<>() {};
        ResponseEntity<ApiResponseErrorDetails<T>> responseEntity = restClient.post()
                .uri(url)
                .headers(h -> h.addAll(headers))
                .body(serializedBody)
                .retrieve()
                .toEntity(responseType);
        ApiResponseErrorDetails<T> body = responseEntity.getBody();
        // A null body means the remote returned success status with no envelope —
        // treat as an external failure so the caller doesn't silently see null.
        if (body == null) {
            throw new ExternalException("RPC response body is null: {0}", responseEntity);
        }
        return handleResponse(body);
    }

    private String buildApiUrl(RPCProperties.ServiceConfig serviceConfig, String modelName, String apiPath) {
        return serviceConfig.getApiUrl() + RPCConstant.RPC_ROUTE + "/" + modelName + "/" + apiPath;
    }

    private HttpHeaders buildHeaders(RPCProperties.ServiceConfig serviceConfig) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set(RPCConstant.RPC_HEADER_API_KEY, serviceConfig.getApiKey());
        headers.set(RPCConstant.RPC_HEADER_API_SECRET, serviceConfig.getApiSecret());
        return headers;
    }

    private String buildRequestBody(Object[] methodArgs) {
        RPCRequestBody requestBody = new RPCRequestBody();
        requestBody.setMethodArgs(methodArgs);
        requestBody.setContext(ContextHolder.cloneContext());
        return SerializeUtils.serialize(requestBody);
    }

    @Override
    public <T> T modelRPC(String serviceName, String modelName, String methodName, Object[] methodArgs) {
        if (StringUtils.isBlank(serviceName)) {
            throw new IllegalStateException("Service name cannot be blank");
        }
        RPCProperties.ServiceConfig serviceConfig = rpcProperties.getServiceConfig(serviceName);
        if (serviceConfig == null || StringUtils.isBlank(serviceConfig.getApiUrl())) {
            throw new IllegalStateException("Service config not valid for service: " + serviceName);
        }
        String url = this.buildApiUrl(serviceConfig, modelName, methodName);
        HttpHeaders headers = buildHeaders(serviceConfig);
        String serializedBody = buildRequestBody(methodArgs);
        try {
            return sendPostRequest(url, headers, serializedBody);
        } catch (RestClientResponseException e) {
            log.error("RPC request {} failed: status={} body={}",
                    url, e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

}

package io.softa.framework.web.rpc;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.ExternalException;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.base.utils.SerializeUtils;
import io.softa.framework.orm.config.RPCProperties;
import io.softa.framework.orm.constant.RpcConstant;
import io.softa.framework.orm.rpc.RemoteApiClient;
import io.softa.framework.web.response.ApiResponseErrorDetails;

@Slf4j
@Component
public class RestTemplateApiClient implements RemoteApiClient {

    @Autowired
    private RestTemplate restTemplate;

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
        HttpEntity<Object> entity = new HttpEntity<>(serializedBody, headers);
        ParameterizedTypeReference<ApiResponseErrorDetails<T>> responseType = new ParameterizedTypeReference<>() {};
        ResponseEntity<ApiResponseErrorDetails<T>> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                responseType
        );
        ApiResponseErrorDetails<T> body = responseEntity.getBody();
        // Check if the response is null, which means network error or service unavailable
        if (body == null) {
            throw new ExternalException("RPC response body is null: {0}", responseEntity);
        } else {
            return handleResponse(body);
        }
    }

    private String buildApiUrl(RPCProperties.ServiceConfig serviceConfig, String modelName, String apiPath) {
        return serviceConfig.getApiUrl() + RpcConstant.RPC_ROUTE + "/" + modelName + "/" + apiPath;
    }

    private HttpHeaders buildHeaders(RPCProperties.ServiceConfig serviceConfig) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set(RpcConstant.RPC_HEADER_API_KEY, serviceConfig.getApiKey());
        headers.set(RpcConstant.RPC_HEADER_API_SECRET, serviceConfig.getApiSecret());
        return headers;
    }

    private String buildRequestBody(Object[] methodArgs) {
        RpcRequestBody requestBody = new RpcRequestBody();
        requestBody.setMethodArgs(methodArgs);
        requestBody.setContext(ContextHolder.cloneContext());
        return SerializeUtils.serialize(requestBody);
    }

    @Override
    public <T> T modelRpc(String serviceName, String modelName, String methodName, Object[] methodArgs) {
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
        } catch (HttpClientErrorException e) {
            log.error("RPC request {} failed: \n {}", url, e.getMessage());
            throw e;
        }
    }

}
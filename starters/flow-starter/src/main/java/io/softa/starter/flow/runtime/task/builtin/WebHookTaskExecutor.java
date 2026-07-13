package io.softa.starter.flow.runtime.task.builtin;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.WebHookConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Built-in ServiceTask executor for calling an external HTTP endpoint (WebHook).
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "WebHook",
 *   "input": {
 *     "url": "https://api.example.com/callback",
 *     "method": "POST",
 *     "headers": { "Authorization": "Bearer {{ token }}" },
 *     "body": { "orderId": "{{ orderId }}", "status": "approved" },
 *     "timeout": 30
 *   },
 *   "outputVariable": "webhookResponse"
 * }
 * }</pre>
 * <p>
 * Uses the resilient {@code flowWebhookRestClient} bean — retries, circuit breaking,
 * and metrics are handled at the HTTP client layer. See
 * {@link FlowWebHookClientConfig} and
 * {@code resilience4j.{retry,circuitbreaker}.instances.flow-webhook}.
 */
@Component
public class WebHookTaskExecutor extends AbstractTaskExecutor {

    private final RestClient restClient;

    public WebHookTaskExecutor(@Qualifier("flowWebhookRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.CALL_WEBHOOK;
    }

    @Override
    public String getExecutor() {
        return "WebHook";
    }

    @Override
    public String getName() {
        return "WebHook";
    }

    @Override
    public String getDescription() {
        return "Call an external HTTP endpoint. Supports GET/POST/PUT/DELETE with headers and JSON body.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.of(
                "url", Map.of("type", "string", "label", "URL", "required", true),
                "method", Map.of("type", "enum", "label", "HTTP Method",
                        "options", List.of("GET", "POST", "PUT", "DELETE"), "default", "POST"),
                "headers", Map.of("type", "keyValueMap", "label", "Headers"),
                "body", Map.of("type", "json", "label", "Body")
        );
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        return Map.of("method", "POST");
    }

    @Override
    public String getIcon() {
        return "globe";
    }

    @Override
    public int getSortOrder() {
        return 70;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        WebHookConfig cfg = requireConfig(request, WebHookConfig.class);
        String url = requireText(cfg.getUrl(), "url");
        String method = cfg.getMethod() != null ? cfg.getMethod().toUpperCase() : "POST";

        // Interpolate URL placeholders
        if (url.contains("{{")) {
            url = ComputeUtils.stringInterpolation(url, new LinkedHashMap<>(variables));
        }

        HttpMethod httpMethod = HttpMethod.valueOf(method);
        URI uri = URI.create(url);
        validateTargetUri(uri);

        // Headers and body carry {{ }} placeholders — resolve them here, since the handler no longer
        // pre-resolves the input (this executor owns its schemaless-payload resolution).
        Object resolvedHeaders = interpolate(cfg.getHeaders(), variables);
        Object body = interpolate(cfg.getBody(), variables);

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> responseEntity = restClient.method(httpMethod)
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (resolvedHeaders instanceof Map<?, ?> headersMap) {
                        headersMap.forEach((k, v) -> headers.set(k.toString(), v != null ? v.toString() : ""));
                    }
                })
                .body(body == null ? "" : body)
                .retrieve()
                .toEntity(Map.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statusCode", responseEntity.getStatusCode().value());
        result.put("body", responseEntity.getBody());
        return result;
    }

    private static void validateTargetUri(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new FlowRuntimeException("WebHook URL must use http or https");
        }
        if (uri.getUserInfo() != null) {
            throw new FlowRuntimeException("WebHook URL must not contain user info");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new FlowRuntimeException("WebHook URL must contain a host");
        }
        String normalizedHost = stripIpv6Brackets(host).toLowerCase();
        if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".localhost")) {
            throw new FlowRuntimeException("WebHook URL must not target localhost");
        }
        if (isIpLiteral(normalizedHost)) {
            validateAddressLiteral(normalizedHost);
        }
    }

    private static String stripIpv6Brackets(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static void validateAddressLiteral(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (isForbiddenAddress(address)) {
                throw new FlowRuntimeException("WebHook URL must not target a private or local address");
            }
        } catch (UnknownHostException e) {
            throw new FlowRuntimeException("WebHook URL host is not valid: " + host, e);
        }
    }

    private static boolean isForbiddenAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isIpv6UniqueLocal(address)
                || isIpv4CarrierGradeNat(address);
    }

    private static boolean isIpv6UniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte first = address.getAddress()[0];
        return (first & (byte) 0xfe) == (byte) 0xfc;
    }

    private static boolean isIpv4CarrierGradeNat(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }
}

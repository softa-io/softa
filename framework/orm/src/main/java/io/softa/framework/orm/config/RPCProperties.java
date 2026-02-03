package io.softa.framework.orm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "rpc")
@Validated
public class RPCProperties {

    private boolean enable;

    // RPC type, e.g. dubbo, grpc, default is http
    private String protocol;

    private String accessKey;

    private String secretKey;

    private Map<String, ServiceConfig> services;

    @Data
    public static class ServiceConfig {
        private String apiUrl;
        private String apiKey;
        private String apiSecret;
    }

    public ServiceConfig getServiceConfig(String serviceName) {
        return services.get(serviceName);
    }
}

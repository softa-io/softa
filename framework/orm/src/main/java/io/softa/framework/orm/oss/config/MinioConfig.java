package io.softa.framework.orm.oss.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.softa.framework.orm.oss.OSSProperties;
import io.softa.framework.orm.oss.OssClientService;
import io.softa.framework.orm.oss.impl.MinioClientService;

@Configuration
@ConditionalOnProperty(value = "oss.type", havingValue = "minio")
public class MinioConfig {

    @Autowired
    private OSSProperties ossProperties;

    @Bean(name = "minioClient")
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(ossProperties.getEndpoint())
                .credentials(ossProperties.getAccessKey(), ossProperties.getSecretKey())
                .build();
    }


    @Bean
    @ConditionalOnBean(MinioClient.class)
    @ConditionalOnMissingBean(OssClientService.class)
    public MinioClientService minioClientService(MinioClient minioClient) {
        return new MinioClientService(minioClient, ossProperties);
    }
}

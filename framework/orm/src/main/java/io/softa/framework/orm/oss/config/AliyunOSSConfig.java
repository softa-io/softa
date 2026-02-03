package io.softa.framework.orm.oss.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.softa.framework.orm.oss.OSSProperties;
import io.softa.framework.orm.oss.OssClientService;
import io.softa.framework.orm.oss.impl.AliyunOSSClientService;

@Configuration
@ConditionalOnProperty(value = "oss.type", havingValue = "aliyun")
public class AliyunOSSConfig {

    @Autowired
    private OSSProperties ossProperties;

    @Bean
    public OSS oss() {
        ClientBuilderConfiguration conf = new ClientBuilderConfiguration();
        conf.setConnectionTimeout(2000);
        conf.setIdleConnectionTime(10000);
        conf.setMaxErrorRetry(0);
        return new OSSClientBuilder()
                .build(ossProperties.getEndpoint(), ossProperties.getAccessKey(), ossProperties.getSecretKey(), conf);
    }

    @Bean
    @ConditionalOnBean({OSS.class})
    @ConditionalOnMissingBean(OssClientService.class)
    public AliyunOSSClientService aliyunOSSClientService(OSS oss) {
        return new AliyunOSSClientService(oss, ossProperties);
    }
}

package io.softa.framework.base.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis configuration
 */
@Configuration
public class RedisConfig {

    @Autowired
    private JsonMapper jsonMapper;

    /**
     * Configure Redis template
     * @param connectionFactory Redis connection factory
     * @return Redis template
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        JacksonJsonRedisSerializer<Object> jsonSerializer = new JacksonJsonRedisSerializer<>(jsonMapper, Object.class);

        StringRedisSerializer  stringSerializer = new StringRedisSerializer();
        // Specify the serializer and deserializer for the key and value
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}

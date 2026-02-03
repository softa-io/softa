package io.softa.framework.orm.service.impl;

import tools.jackson.core.type.TypeReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.service.CacheService;

/**
 * Cache service implementation.
 * Support saving, searching, and deleting cache by key.
 */
@Service
@Slf4j
public class CacheServiceImpl implements CacheService {

    /**
     * Root key, such as: "softa:"
     */
    @Value("${spring.data.redis.root-key:}")
    private String rootKey;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * Get the full key path.
     *
     * @param key cache key
     */
    @Override
    public String getKeyPath(String key) {
        if (StringUtils.isBlank(rootKey)) {
            return key;
        }
        return rootKey + ":" + key;
    }

    /**
     * Save cache, use default expiration time.
     *
     * @param key    cache key
     * @param object cache object
     */
    @Override
    public void save(String key, Object object) {
        String cacheKey = this.getKeyPath(key);
        this.save(cacheKey, object, RedisConstant.DEFAULT_EXPIRE_SECONDS);
    }

    /**
     * Save cache, specify expiration time in seconds, 0 means permanent validity.
     *
     * @param key           cache key
     * @param object        cache object
     * @param expireSeconds expiration time in seconds
     */
    @Override
    public void save(String key, Object object, int expireSeconds) {
        String cacheKey = this.getKeyPath(key);
        String value = JsonUtils.objectToString(object);
        if (expireSeconds < 0) {
            log.warn("Invalid expiration time, use default expiration seconds: {}",
                    RedisConstant.DEFAULT_EXPIRE_SECONDS);
            expireSeconds = RedisConstant.DEFAULT_EXPIRE_SECONDS;
        }
        stringRedisTemplate.opsForValue().set(cacheKey, value, expireSeconds, TimeUnit.SECONDS);
    }

    /**
     * Search cache by key list.
     *
     * @param keys key list
     * @return key-value map
     */
    @Override
    public Map<String, Object> search(List<String> keys) {
        Map<String, Object> map = new HashMap<>();
        for (String key : keys) {
            String cacheKey = this.getKeyPath(key);
            map.put(key, stringRedisTemplate.opsForValue().get(cacheKey));
        }
        return map;
    }

    /**
     * Check if the key exists.
     *
     * @param key cache key
     * @return true or false
     */
    @Override
    public boolean hasKey(String key) {
        String cacheKey = this.getKeyPath(key);
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey));
    }

    /**
     * Get cache by key.
     *
     * @param key cache key
     * @return cache
     */
    @Override
    public String get(String key) {
        String cacheKey = this.getKeyPath(key);
        return stringRedisTemplate.opsForValue().get(cacheKey);
    }

    /**
     * Get cache object by key, specify class for deserialization.
     *
     * @param key    cache key
     * @param tClass class
     * @param <T>    T
     * @return cache object
     */
    @Override
    public <T> T get(String key, Class<T> tClass) {
        String cacheKey = this.getKeyPath(key);
        String value = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.isNotBlank(value)) {
            return JsonUtils.stringToObject(value, tClass);
        }
        return null;
    }

    /**
     * Get cache object by key, specify TypeReference for deserialization.
     *
     * @param key           cache key
     * @param typeReference TypeReference
     * @param <T>           T
     * @return cache object
     */
    @Override
    public <T> T get(String key, TypeReference<T> typeReference) {
        String cacheKey = this.getKeyPath(key);
        String value = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.isNotBlank(value)) {
            return JsonUtils.stringToObject(value, typeReference);
        }
        return null;
    }

    /**
     * Get cache object by key, specify TypeReference for deserialization, and
     * return default value if not found.
     *
     * @param key           cache key
     * @param typeReference TypeReference
     * @param defaultValue  default value
     * @param <T>           T
     * @return cache object
     */
    @Override
    public <T> T get(String key, TypeReference<T> typeReference, T defaultValue) {
        String cacheKey = this.getKeyPath(key);
        String value = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.isNotBlank(value)) {
            return JsonUtils.stringToObject(value, typeReference);
        }
        return defaultValue;
    }

    /**
     * Increment count.
     * If the key does not exist, set the initial value and expiration time.
     *
     * @param key            cache key
     * @param expiredSeconds expired seconds
     * @return count
     */
    @Override
    public Long increment(String key, long expiredSeconds) {
        String cacheKey = this.getKeyPath(key);
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey))) {
            return valueOperations.increment(cacheKey);
        } else {
            // Set the initial value and expiration time in seconds.
            valueOperations.set(cacheKey, "1", expiredSeconds, TimeUnit.SECONDS);
            return 1L;
        }
    }

    /**
     * Clear cache by key.
     *
     * @param key cache key
     */
    @Override
    public void clear(String key) {
        String cacheKey = this.getKeyPath(key);
        stringRedisTemplate.delete(cacheKey);
    }

    /**
     * Clear key list.
     *
     * @param keys key list
     * @return count
     */
    @Override
    public Long clear(List<String> keys) {
        List<String> cacheKeys = keys.stream().map(this::getKeyPath).collect(Collectors.toList());
        return stringRedisTemplate.delete(cacheKeys);
    }

}

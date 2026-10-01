package io.softa.framework.orm.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
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
     * Atomically INCR the counter and, when this call creates it (count == 1), apply the TTL in
     * the same server-side step. Splitting INCR and EXPIRE client-side leaves a window where the
     * key is created without an expiration, so the counter would accumulate forever.
     */
    private static final RedisScript<Long> INCREMENT_SCRIPT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    /** Keys requested per SCAN step, and removed per UNLINK. */
    private static final int SCAN_BATCH = 1000;

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
        this.save(key, object, RedisConstant.DEFAULT_EXPIRE_SECONDS);
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
        if (expireSeconds == 0) {
            // 0 means permanent validity: plain SET, since SETEX rejects a zero timeout.
            stringRedisTemplate.opsForValue().set(cacheKey, value);
        } else {
            stringRedisTemplate.opsForValue().set(cacheKey, value, Duration.ofSeconds(expireSeconds));
        }
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
     * The increment and the first-write expiration run as one atomic server-side script,
     * so concurrent callers cannot lose a count or leave the counter without a TTL.
     *
     * @param key            cache key
     * @param expiredSeconds expired seconds
     * @return count
     */
    @Override
    public Long increment(String key, long expiredSeconds) {
        String cacheKey = this.getKeyPath(key);
        return stringRedisTemplate.execute(INCREMENT_SCRIPT, List.of(cacheKey), String.valueOf(expiredSeconds));
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

    /**
     * Clear every key under a prefix, joined to the root key.
     *
     * @param prefix key prefix, not blank
     * @return number of keys removed
     */
    @Override
    public long clearByPrefix(String prefix) {
        if (StringUtils.isBlank(prefix)) {
            throw new IllegalArgumentException("A key prefix is required: a blank one would clear every key.");
        }
        ScanOptions options = ScanOptions.scanOptions()
                .match(escapeGlob(this.getKeyPath(prefix)) + "*")
                .count(SCAN_BATCH)
                .build();
        long removed = 0;
        List<String> batch = new ArrayList<>(SCAN_BATCH);
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_BATCH) {
                    removed += unlink(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            removed += unlink(batch);
        }
        return removed;
    }

    private long unlink(List<String> keys) {
        Long count = stringRedisTemplate.unlink(keys);
        return count == null ? 0 : count;
    }

    /**
     * Escape the characters SCAN's MATCH reads as a glob, so a prefix matches only itself.
     * Package-private for the test.
     */
    static String escapeGlob(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (char c : literal.toCharArray()) {
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

}

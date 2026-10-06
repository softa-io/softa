package io.softa.framework.orm.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.constant.RedisConstant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the root-key prefixing contract: the prefix is applied exactly once,
 * in the overload that touches Redis, so writes and reads resolve the same
 * physical key.
 *
 * <p>Regression: the default-TTL {@code save(key, object)} used to resolve
 * the key path before delegating to {@code save(key, object, seconds)},
 * which resolved it again — writes landed on {@code root:root:key} while
 * reads looked up {@code root:key}, so those cache entries never hit.
 */
class CacheServiceImplTest {

    private static final String ROOT_KEY = "softa";

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private CacheServiceImpl cacheService;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOperations = Mockito.mock();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new CacheServiceImpl();
        ReflectionTestUtils.setField(cacheService, "rootKey", ROOT_KEY);
        ReflectionTestUtils.setField(cacheService, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void saveWithDefaultTtlPrefixesRootKeyOnce() {
        cacheService.save("tenant:info:1", "cached");

        verify(valueOperations).set(eq(ROOT_KEY + ":tenant:info:1"), anyString(),
                eq(Duration.ofSeconds(RedisConstant.DEFAULT_EXPIRE_SECONDS)));
    }

    @Test
    void saveAndGetResolveTheSamePhysicalKey() {
        cacheService.save("tenant:info:1", "cached");
        cacheService.get("tenant:info:1");

        verify(valueOperations).set(eq(ROOT_KEY + ":tenant:info:1"), anyString(),
                eq(Duration.ofSeconds(RedisConstant.DEFAULT_EXPIRE_SECONDS)));
        verify(valueOperations).get(ROOT_KEY + ":tenant:info:1");
    }

    @Test
    void saveWithZeroSecondsWritesWithoutTtl() {
        cacheService.save("tenant:info:1", "cached", 0);

        verify(valueOperations).set(eq(ROOT_KEY + ":tenant:info:1"), anyString());
    }

    @Test
    void incrementRunsTheAtomicScriptOnThePrefixedKey() {
        when(stringRedisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(ROOT_KEY + ":login:attempts:42")), eq("30"))).thenReturn(2L);

        assertEquals(2L, cacheService.increment("login:attempts:42", 30));
    }

    @Test
    void blankRootKeyLeavesKeyUntouched() {
        ReflectionTestUtils.setField(cacheService, "rootKey", "");

        assertEquals("tenant:info:1", cacheService.getKeyPath("tenant:info:1"));
    }

    @Test
    void clearByPrefixScansOnlyUnderTheRootKey() {
        stubScan(List.of(ROOT_KEY + ":perm:1:user:1", ROOT_KEY + ":perm:1:user:2"));
        when(stringRedisTemplate.unlink(ArgumentMatchers.<List<String>>any())).thenReturn(2L);

        assertEquals(2L, cacheService.clearByPrefix("perm:"));

        ArgumentCaptor<ScanOptions> options = ArgumentCaptor.forClass(ScanOptions.class);
        verify(stringRedisTemplate).scan(options.capture());
        assertEquals(ROOT_KEY + ":perm:*", options.getValue().getPattern());
        verify(stringRedisTemplate).unlink(List.of(ROOT_KEY + ":perm:1:user:1", ROOT_KEY + ":perm:1:user:2"));
    }

    @Test
    void clearByPrefixUnlinksInBatches() {
        List<String> keys = IntStream.range(0, 2500).mapToObj(i -> ROOT_KEY + ":entl:" + i).toList();
        stubScan(keys);
        List<Integer> batchSizes = new ArrayList<>();
        when(stringRedisTemplate.unlink(ArgumentMatchers.<List<String>>any())).thenAnswer(call -> {
            List<String> batch = call.getArgument(0);
            batchSizes.add(batch.size());
            return (long) batch.size();
        });

        assertEquals(2500L, cacheService.clearByPrefix("entl:"));
        assertEquals(List.of(1000, 1000, 500), batchSizes);
    }

    @Test
    void clearByPrefixWithNothingToClearUnlinksNothing() {
        stubScan(List.of());

        assertEquals(0L, cacheService.clearByPrefix("perm:"));
        verify(stringRedisTemplate, Mockito.never()).unlink(ArgumentMatchers.<List<String>>any());
    }

    @Test
    void clearByPrefixRefusesABlankPrefix() {
        assertThrows(RuntimeException.class, () -> cacheService.clearByPrefix(" "));
        verify(stringRedisTemplate, Mockito.never()).scan(ArgumentMatchers.any(ScanOptions.class));
    }

    @Test
    void globCharactersInAPrefixMatchOnlyThemselves() {
        assertEquals("a\\*b\\?c\\[d\\]e\\\\f", CacheServiceImpl.escapeGlob("a*b?c[d]e\\f"));
    }

    @SuppressWarnings("unchecked")
    private void stubScan(List<String> keys) {
        Iterator<String> iterator = keys.iterator();
        Cursor<String> cursor = Mockito.mock(Cursor.class);
        when(cursor.hasNext()).thenAnswer(call -> iterator.hasNext());
        when(cursor.next()).thenAnswer(call -> iterator.next());
        when(stringRedisTemplate.scan(ArgumentMatchers.any(ScanOptions.class))).thenReturn(cursor);
    }
}

package com.sky.service.cache;

import com.sky.vo.OrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCacheServiceTest {

    private static final String CACHE_KEY = "order:detail:22";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private OrderCacheService orderCacheService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        orderCacheService = new OrderCacheService(stringRedisTemplate);
        ReflectionTestUtils.setField(orderCacheService, "ttlMinutes", 30L);
        ReflectionTestUtils.setField(orderCacheService, "ttlJitterMinutes", 10L);
        ReflectionTestUtils.setField(orderCacheService, "nullTtlMinutes", 2L);
        ReflectionTestUtils.setField(orderCacheService, "lockTtlSeconds", 10L);
        ReflectionTestUtils.setField(orderCacheService, "lockRetryIntervalMillis", 1L);
        ReflectionTestUtils.setField(orderCacheService, "lockRetryTimes", 1);
    }

    @Test
    void shouldReturnCachedOrderWithoutQueryingDatabase() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("{\"id\":22,\"number\":\"ORDER-22\"}");
        AtomicInteger databaseQueries = new AtomicInteger();

        OrderVO result = orderCacheService.query(22L, () -> {
            databaseQueries.incrementAndGet();
            return null;
        });

        assertEquals(22L, result.getId());
        assertEquals("ORDER-22", result.getNumber());
        assertEquals(0, databaseQueries.get());
    }

    @Test
    void shouldUseNullValueCacheToPreventPenetration() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("__NULL__");
        AtomicInteger databaseQueries = new AtomicInteger();

        OrderVO result = orderCacheService.query(22L, () -> {
            databaseQueries.incrementAndGet();
            return new OrderVO();
        });

        assertNull(result);
        assertEquals(0, databaseQueries.get());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldRebuildCacheWithMutexAndRandomTtl() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq("order:detail:lock:22"),
                anyString(),
                any(Duration.class))).thenReturn(true);
        AtomicInteger databaseQueries = new AtomicInteger();

        OrderVO result = orderCacheService.query(22L, () -> {
            databaseQueries.incrementAndGet();
            OrderVO orderVO = new OrderVO();
            orderVO.setId(22L);
            return orderVO;
        });

        assertEquals(22L, result.getId());
        assertEquals(1, databaseQueries.get());

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(CACHE_KEY), anyString(), durationCaptor.capture());
        long ttlMinutes = durationCaptor.getValue().toMinutes();
        assertTrue(ttlMinutes >= 30 && ttlMinutes <= 40);
        verify(stringRedisTemplate).execute(any(), eq(java.util.Collections.singletonList("order:detail:lock:22")), anyString());
    }

    @Test
    void shouldCacheMissingOrderForShortTime() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq("order:detail:lock:22"),
                anyString(),
                any(Duration.class))).thenReturn(true);

        OrderVO result = orderCacheService.query(22L, () -> null);

        assertNull(result);
        verify(valueOperations).set(CACHE_KEY, "__NULL__", Duration.ofMinutes(2));
    }
}

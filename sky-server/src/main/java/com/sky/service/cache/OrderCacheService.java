package com.sky.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.json.JacksonObjectMapper;
import com.sky.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 订单详情缓存。
 *
 * <p>空值缓存防止缓存穿透，Redis 互斥锁防止热点缓存击穿，
 * 基础过期时间叠加随机抖动防止大量缓存同时失效造成缓存雪崩。</p>
 */
@Component
@Slf4j
public class OrderCacheService {

    private static final String ORDER_DETAIL_KEY_PREFIX = "order:detail:";
    private static final String ORDER_LOCK_KEY_PREFIX = "order:detail:lock:";
    private static final String NULL_VALUE = "__NULL__";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new JacksonObjectMapper();

    @Value("${sky.order-cache.ttl-minutes:30}")
    private long ttlMinutes;

    @Value("${sky.order-cache.ttl-jitter-minutes:10}")
    private long ttlJitterMinutes;

    @Value("${sky.order-cache.null-ttl-minutes:2}")
    private long nullTtlMinutes;

    @Value("${sky.order-cache.lock-ttl-seconds:10}")
    private long lockTtlSeconds;

    @Value("${sky.order-cache.lock-retry-interval-millis:50}")
    private long lockRetryIntervalMillis;

    @Value("${sky.order-cache.lock-retry-times:10}")
    private int lockRetryTimes;

    public OrderCacheService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 查询订单详情缓存；Redis 不可用时自动降级为查询数据库，不影响主业务。
     */
    public OrderVO query(Long orderId, Supplier<OrderVO> databaseLoader) {
        String cacheKey = buildCacheKey(orderId);

        try {
            CacheValue cached = readCache(cacheKey);
            if (cached.isPresent()) {
                return cached.getValue();
            }
        } catch (CacheUnavailableException ex) {
            log.warn("Redis不可用，订单详情查询降级到数据库，orderId={}", orderId, ex.getCause());
            return databaseLoader.get();
        }

        for (int retry = 0; retry <= lockRetryTimes; retry++) {
            String lockToken = UUID.randomUUID().toString();
            boolean locked;
            try {
                locked = tryLock(orderId, lockToken);
            } catch (CacheUnavailableException ex) {
                log.warn("Redis不可用，订单详情查询降级到数据库，orderId={}", orderId, ex.getCause());
                return databaseLoader.get();
            }

            if (locked) {
                try {
                    // 双重检查，避免等待锁期间其他线程已经完成缓存重建。
                    CacheValue cached = readCache(cacheKey);
                    if (cached.isPresent()) {
                        return cached.getValue();
                    }

                    OrderVO orderVO = databaseLoader.get();
                    writeCache(cacheKey, orderVO);
                    return orderVO;
                } catch (CacheUnavailableException ex) {
                    log.warn("Redis不可用，订单详情查询降级到数据库，orderId={}", orderId, ex.getCause());
                    return databaseLoader.get();
                } finally {
                    unlock(orderId, lockToken);
                }
            }

            if (retry < lockRetryTimes) {
                try {
                    Thread.sleep(lockRetryIntervalMillis);
                    CacheValue cached = readCache(cacheKey);
                    if (cached.isPresent()) {
                        return cached.getValue();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (CacheUnavailableException ex) {
                    log.warn("Redis不可用，订单详情查询降级到数据库，orderId={}", orderId, ex.getCause());
                    return databaseLoader.get();
                }
            }
        }

        // 锁竞争持续时间过长时直接回源，避免接口长时间阻塞。
        return databaseLoader.get();
    }

    /**
     * 数据修改成功后删除缓存；存在事务时延迟到事务提交后执行，避免回滚造成缓存丢失或脏数据。
     */
    public void evictAfterCommit(Long orderId) {
        if (orderId == null) {
            return;
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(orderId);
                }
            });
        } else {
            evict(orderId);
        }
    }

    public void evict(Long orderId) {
        if (orderId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(buildCacheKey(orderId));
        } catch (RuntimeException ex) {
            // 删除缓存失败不能回滚已经成功的订单业务，记录日志交由短 TTL 自愈。
            log.error("删除订单缓存失败，orderId={}", orderId, ex);
        }
    }

    private CacheValue readCache(String cacheKey) {
        final String json;
        try {
            json = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException ex) {
            throw new CacheUnavailableException(ex);
        }

        if (json == null) {
            return CacheValue.miss();
        }
        if (NULL_VALUE.equals(json)) {
            return CacheValue.hit(null);
        }

        try {
            return CacheValue.hit(objectMapper.readValue(json, OrderVO.class));
        } catch (JsonProcessingException ex) {
            log.warn("订单缓存内容无法解析，将删除后重新构建，key={}", cacheKey, ex);
            try {
                stringRedisTemplate.delete(cacheKey);
            } catch (RuntimeException deleteException) {
                throw new CacheUnavailableException(deleteException);
            }
            return CacheValue.miss();
        }
    }

    private void writeCache(String cacheKey, OrderVO orderVO) {
        try {
            if (orderVO == null) {
                stringRedisTemplate.opsForValue().set(
                        cacheKey,
                        NULL_VALUE,
                        Duration.ofMinutes(Math.max(1, nullTtlMinutes)));
                return;
            }

            long jitter = ttlJitterMinutes <= 0
                    ? 0
                    : ThreadLocalRandom.current().nextLong(ttlJitterMinutes + 1);
            long timeout = Math.max(1, ttlMinutes + jitter);
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(orderVO),
                    Duration.ofMinutes(timeout));
        } catch (JsonProcessingException | RuntimeException ex) {
            throw new CacheUnavailableException(ex);
        }
    }

    private boolean tryLock(Long orderId, String token) {
        try {
            Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(
                    buildLockKey(orderId),
                    token,
                    Duration.ofSeconds(Math.max(1, lockTtlSeconds)));
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException ex) {
            throw new CacheUnavailableException(ex);
        }
    }

    private void unlock(Long orderId, String token) {
        try {
            stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(buildLockKey(orderId)),
                    token);
        } catch (RuntimeException ex) {
            log.warn("释放订单缓存互斥锁失败，orderId={}", orderId, ex);
        }
    }

    private String buildCacheKey(Long orderId) {
        return ORDER_DETAIL_KEY_PREFIX + orderId;
    }

    private String buildLockKey(Long orderId) {
        return ORDER_LOCK_KEY_PREFIX + orderId;
    }

    private static class CacheValue {
        private final boolean present;
        private final OrderVO value;

        private CacheValue(boolean present, OrderVO value) {
            this.present = present;
            this.value = value;
        }

        static CacheValue hit(OrderVO value) {
            return new CacheValue(true, value);
        }

        static CacheValue miss() {
            return new CacheValue(false, null);
        }

        boolean isPresent() {
            return present;
        }

        OrderVO getValue() {
            return value;
        }
    }

    private static class CacheUnavailableException extends RuntimeException {
        CacheUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}

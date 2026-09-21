package com.ll.hirehub.job.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis SETNX 的互斥锁（缓存击穿防护：热点 key 失效时只允许一个线程重建，见 §6.3）。
 * 与 delivery 的 RedisLock 同实现——common 不引 redis（避免把 redis 带进网关），故各服务持有一份。
 */
@Component
@RequiredArgsConstructor
public class RedisLock {

    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", ttl));
    }
}

package com.ll.hirehub.interview.mq;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis SETNX 的简易分布式锁（定时任务防多实例并发）。
 * 与 delivery 侧同名类同实现：TTL 兜底「持锁实例崩了」，到期自然释放。
 * 局限：非重入、无自动续期；任务耗时必须显著小于 TTL。
 */
@Component
@RequiredArgsConstructor
public class RedisLock {

    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", ttl));
    }
}

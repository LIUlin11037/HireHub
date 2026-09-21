package com.ll.hirehub.delivery.mq;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis SETNX 的简易分布式锁（用于定时任务防多实例并发，见架构文档 §6.2 步骤②）。
 * <p>
 * 用 TTL 兜底「持锁实例崩了」的场景，因此不需要释放锁——到期自然释放。
 * 局限（面试可讲）：非重入、无自动续期；任务耗时必须显著小于 TTL，
 * 否则会出现两个实例同时执行。生产级可用 Redisson 的看门狗续期。
 */
@Component
@RequiredArgsConstructor
public class RedisLock {

    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", ttl));
    }
}

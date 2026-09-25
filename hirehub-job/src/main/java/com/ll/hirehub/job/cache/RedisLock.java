package com.ll.hirehub.job.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * 基于 Redis SETNX 的互斥锁（缓存击穿防护：热点 key 失效时只允许一个线程重建，见 §6.3）。
 * 与 delivery 的 RedisLock 同实现——common 不引 redis（避免把 redis 带进网关），故各服务持有一份。
 */
@Component
@RequiredArgsConstructor
public class RedisLock {

    /** 解锁必须"比对持有者 + 删除"原子完成，否则可能删掉别人的锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(String key, Duration ttl) {
        return tryLock(key, "1", ttl);
    }

    /**
     * 带持有者标识的加锁。
     * <p>
     * 为什么需要 token：见 {@link #unlock} —— 主动解锁时只能解自己的锁。
     */
    public boolean tryLock(String key, String token, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl));
    }

    /**
     * 释放自己持有的锁（compare-and-delete）。
     * <p>
     * <b>为什么不能只靠 TTL 释放</b>：TTL 是为了"持锁者崩了也不会永久死锁"的兜底，
     * 不是正常路径。若重建完不主动释放，锁会一直占满整个 TTL，
     * 期间任何一次缓存失效都会让后续读请求抢不到锁、又读到空缓存，
     * 最终把"缓存里没有"误判成"记录不存在"（见踩坑记录 #36）。
     *
     * @return true 表示确实是自己持有并已删除
     */
    public boolean unlock(String key, String token) {
        Long deleted = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        return deleted != null && deleted > 0;
    }
}

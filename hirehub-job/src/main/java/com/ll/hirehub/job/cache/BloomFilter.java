package com.ll.hirehub.job.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 基于 Redis bitmap 的布隆过滤器（见架构文档 §6.3 缓存三防之「缓存穿透」）。
 * <p>
 * 用 SETBIT/GETBIT 而不是 Guava 内存版：多实例共享同一份位图，避免每个实例各维护一份。
 * <p>
 * 布隆特性：可能误报（把不存在的判成可能存在），但绝不漏报——
 * {@code mightContain == false} 一定不存在，所以可以放心地不打 DB 直接返回 404。
 *
 * @param bitSize 位图长度（bit），越大误报率越低、占用内存越多
 * @param hashNum 哈希函数个数
 */
@Component
@RequiredArgsConstructor
public class BloomFilter {

    private static final String KEY = "job:bloom";
    private static final int BIT_SIZE = 1 << 20;   // 1M bit = 128KB
    private static final int HASH_NUM = 3;

    private final StringRedisTemplate redisTemplate;

    public void add(Long id) {
        for (int i = 0; i < HASH_NUM; i++) {
            int offset = hash(id, i);
            redisTemplate.opsForValue().setBit(KEY, offset, true);
        }
    }

    public boolean mightContain(Long id) {
        for (int i = 0; i < HASH_NUM; i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(KEY, hash(id, i));
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    /** 双重哈希：h = (h1 + i*h2) & (bitSize-1)，避免多次独立哈希的成本，同时保证分散性 */
    private int hash(Long id, int i) {
        int h1 = id.hashCode();
        int h2 = (h1 >>> 16) ^ 0x9E3779B9;   // 与黄金比混合
        return ((h1 + i * h2) & (BIT_SIZE - 1));
    }

    @SuppressWarnings("unused")
    private static int hashCode(String s) {
        return java.util.Arrays.hashCode(s.getBytes(StandardCharsets.UTF_8));
    }
}

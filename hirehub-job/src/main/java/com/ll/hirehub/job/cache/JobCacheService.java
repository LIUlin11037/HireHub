package com.ll.hirehub.job.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.hirehub.common.exception.BusinessException;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 职位详情缓存，落地 §6.3 的「缓存三防」：
 * <ol>
 *   <li><b>穿透</b>：布隆过滤器判定不存在的 id 直接返回 null，不打 DB；
 *       真不存在的再补一条空值缓存（短 TTL）。</li>
 *   <li><b>击穿</b>：热点 key 过期后，互斥锁保证只有一个线程重建，其余线程读到旧值或稍后重试。</li>
 *   <li><b>雪崩</b>：TTL 加随机扰动，避免同一批 key 同时过期集中打 DB。</li>
 * </ol>
 * <p>
 * 只缓存对外读接口（JobController.get）；Feign 的 /internal/{id} 仍直连 DB——
 * 因为投递服务需要的 status/companyId 是强一致读，绝不能拿旧缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobCacheService {

    private static final String CACHE_PREFIX = "job:cache:";
    private static final String EMPTY = "__EMPTY__";
    private static final Duration BASE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final JobMapper jobMapper;
    private final BloomFilter bloomFilter;
    private final RedisLock redisLock;
    private final ObjectMapper objectMapper;

    /** @return null 表示职位不存在 */
    public Job getCached(Long id) {
        // ① 穿透：布隆判定不存在 → 直接返回，不打 DB
        if (!bloomFilter.mightContain(id)) {
            return null;
        }
        String key = CACHE_PREFIX + id;
        String raw = redisTemplate.opsForValue().get(key);
        if (raw != null) {
            return EMPTY.equals(raw) ? null : read(raw);
        }
        // ② 击穿：加互斥锁，只放一个线程重建
        if (redisLock.tryLock("lock:" + key, Duration.ofSeconds(30))) {
            try {
                raw = redisTemplate.opsForValue().get(key);   // double-check
                if (raw != null) {
                    return EMPTY.equals(raw) ? null : read(raw);
                }
                Job job = jobMapper.selectById(id);
                if (job == null) {
                    // 空值缓存：短 TTL，拦住「反复查同一个不存在 id」的穿透
                    redisTemplate.opsForValue().set(key, EMPTY, jittered(Duration.ofMinutes(1)));
                    return null;
                }
                // ③ 雪崩：TTL 随机扰动
                redisTemplate.opsForValue().set(key, write(job), jittered(BASE_TTL));
                return job;
            } finally {
                // 锁靠 TTL 自动释放，无需手动删（见 RedisLock）
            }
        }
        // 没抢到锁：别人正在重建，短暂等待后读一次缓存
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        raw = redisTemplate.opsForValue().get(key);
        return raw == null ? null : (EMPTY.equals(raw) ? null : read(raw));
    }

    /** 职位变更（上线/下线/删除）后主动失效缓存，避免读到旧状态 */
    public void evict(Long id) {
        redisTemplate.delete(CACHE_PREFIX + id);
    }

    /** TTL 加 0%~10% 随机扰动 */
    private Duration jittered(Duration base) {
        long seconds = base.toSeconds();
        long delta = ThreadLocalRandom.current().nextLong(0, Math.max(1, seconds / 10) + 1);
        return base.plusSeconds(delta);
    }

    private String write(Job job) {
        try {
            return objectMapper.writeValueAsString(job);
        } catch (JsonProcessingException e) {
            throw new BusinessException("职位缓存序列化失败");
        }
    }

    private Job read(String raw) {
        try {
            return objectMapper.readValue(raw, Job.class);
        } catch (JsonProcessingException e) {
            log.warn("职位缓存反序列化失败，删除缓存: {}", e.getMessage());
            return null;
        }
    }
}

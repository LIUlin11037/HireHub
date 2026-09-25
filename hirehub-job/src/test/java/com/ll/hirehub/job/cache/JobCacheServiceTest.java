package com.ll.hirehub.job.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ll.hirehub.job.entity.Job;
import com.ll.hirehub.job.mapper.JobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 职位详情缓存（§6.3 缓存三防）。
 * <p>
 * <b>第一个用例是回归测试</b>：端到端验证时发现过一个真实缺陷 ——
 * 击穿锁只靠 TTL 释放、且"抢不到锁"时直接返回 null，
 * 于是「读一次 → 写侧 evict → 立刻再读」这条时序会让**一个确实存在的职位返回 404**。
 * 端到端脚本能发现它，但很难每次都复现（依赖 30 秒锁窗口），所以在这里用单测钉死。
 */
@ExtendWith(MockitoExtension.class)
class JobCacheServiceTest {

    private static final long JOB_ID = 7L;
    private static final String CACHE_KEY = "job:cache:7";
    private static final String LOCK_KEY = "lock:job:cache:7";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private JobMapper jobMapper;
    @Mock
    private BloomFilter bloomFilter;
    @Mock
    private RedisLock redisLock;

    private ObjectMapper objectMapper;
    private JobCacheService cache;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        cache = new JobCacheService(redisTemplate, jobMapper, bloomFilter, redisLock, objectMapper);
        // lenient：这是共享夹具。并非每个用例都会读到 Redis
        //（例如布隆过滤器直接短路、以及 evict 只调 delete），
        // 严格模式下会把这些用例误判成「多余 stub」而报 UnnecessaryStubbing。
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private Job job(long id) {
        Job job = new Job();
        job.setId(id);
        job.setCompanyId(1L);
        job.setTitle("Java Engineer");
        job.setStatus(1);
        return job;
    }

    @Test
    @DisplayName("★ 回归：缓存已被写侧清掉、又抢不到重建锁时，必须落 DB 兜底，不能返回 null(=404)")
    void fallsBackToDatabaseInsteadOfReportingNotFound() {
        Job inDb = job(JOB_ID);
        when(bloomFilter.mightContain(JOB_ID)).thenReturn(true);
        when(valueOps.get(CACHE_KEY)).thenReturn(null);            // evict 之后缓存为空
        when(redisLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(false);
        when(jobMapper.selectById(JOB_ID)).thenReturn(inDb);

        assertThat(cache.getCached(JOB_ID))
                .as("缓存为空不等于记录不存在：返回 null 会让 JobService 抛 10004")
                .isSameAs(inDb);
    }

    @Test
    @DisplayName("★ 重建成功后必须主动释放锁（否则锁占满 TTL，制造 30 秒无法重建窗口）")
    void releasesLockAfterSuccessfulRebuild() {
        Job inDb = job(JOB_ID);
        when(bloomFilter.mightContain(JOB_ID)).thenReturn(true);
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(redisLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(jobMapper.selectById(JOB_ID)).thenReturn(inDb);

        assertThat(cache.getCached(JOB_ID)).isSameAs(inDb);
        verify(redisLock).unlock(eq(LOCK_KEY), anyString());
        verify(valueOps).set(eq(CACHE_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("缓存命中直接返回，不落 DB")
    void cacheHitSkipsDatabase() throws Exception {
        Job inDb = job(JOB_ID);
        when(bloomFilter.mightContain(JOB_ID)).thenReturn(true);
        when(valueOps.get(CACHE_KEY)).thenReturn(objectMapper.writeValueAsString(inDb));

        assertThat(cache.getCached(JOB_ID).getTitle()).isEqualTo("Java Engineer");
        verifyNoInteractions(jobMapper);
    }

    @Test
    @DisplayName("布隆过滤器判定不存在 → 直接返回，Redis 与 DB 都不打（防穿透）")
    void bloomFilterShortCircuitsMisses() {
        when(bloomFilter.mightContain(99L)).thenReturn(false);

        assertThat(cache.getCached(99L)).isNull();
        verifyNoInteractions(redisTemplate, jobMapper, redisLock);
    }

    @Test
    @DisplayName("记录确实不存在 → 写空值缓存（短 TTL）并返回 null，同时释放锁")
    void missingRowCachesEmptySentinel() {
        when(bloomFilter.mightContain(JOB_ID)).thenReturn(true);
        when(valueOps.get(CACHE_KEY)).thenReturn(null);
        when(redisLock.tryLock(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(jobMapper.selectById(JOB_ID)).thenReturn(null);

        assertThat(cache.getCached(JOB_ID)).isNull();
        verify(valueOps).set(eq(CACHE_KEY), eq("__EMPTY__"), any(Duration.class));
        verify(redisLock).unlock(eq(LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("空值缓存被命中时视为不存在（不落 DB）")
    void emptySentinelMeansNotFound() {
        when(bloomFilter.mightContain(JOB_ID)).thenReturn(true);
        when(valueOps.get(CACHE_KEY)).thenReturn("__EMPTY__");

        assertThat(cache.getCached(JOB_ID)).isNull();
        verifyNoInteractions(jobMapper);
    }

    @Test
    @DisplayName("evict 只删缓存键，不动锁")
    void evictDeletesCacheKeyOnly() {
        cache.evict(JOB_ID);

        verify(redisTemplate).delete(CACHE_KEY);
        verify(redisLock, never()).unlock(anyString(), anyString());
    }
}

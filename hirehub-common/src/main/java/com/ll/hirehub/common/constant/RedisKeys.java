package com.ll.hirehub.common.constant;

/**
 * Redis key 常量集中（审计 Q-05，同 {@code MqConst} 风格）。
 * <p>
 * 散落的硬编码 key 有两个实际风险：① 同一逻辑 key 多处各写一份（如 access 黑名单 key 曾同时
 * 硬编码在 auth 的 TokenStore 和网关的 AuthGlobalFilter），改一处漏一处即失去一致性；
 * ② 排查时无法在全局视图里看到"我们都用了哪些 key 空间"。
 * <p>
 * 约定：带参数的 key 用 {@code String.format} 模板（%d/%s），前缀类常量以冒号结尾。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // ---------------- auth ----------------

    /** 登录失败计数（LoginAttemptGuard，key = 前缀 + 用户名|IP） */
    public static final String AUTH_LOGIN_FAIL_PREFIX = "auth:login:fail:";
    /** 登录锁定标记（LoginAttemptGuard，key = 前缀 + 用户名|IP） */
    public static final String AUTH_LOGIN_LOCK_PREFIX = "auth:login:lock:";
    /** refresh token 白名单（TokenStore）：userId + jti */
    public static final String AUTH_REFRESH_KEY = "auth:refresh:%d:%s";
    /** refresh token 用户索引（TokenStore）：userId */
    public static final String AUTH_REFRESH_INDEX_KEY = "auth:refresh:index:%d";
    /** access token 黑名单（TokenStore 写入 / 网关 AuthGlobalFilter 校验，共用同一 key 空间）：jti */
    public static final String AUTH_BLACKLIST_KEY = "auth:blacklist:%s";

    // ---------------- delivery ----------------

    /** 投递幂等占位（DeliveryService，key = 前缀 + jobId:resumeId） */
    public static final String DELIVERY_IDEM_PREFIX = "delivery:idem:";
    /** 本地消息表定时任务分布式锁（delivery 侧 LocalMessageRelay） */
    public static final String LOCK_LOCAL_MESSAGE = "lock:local-message";

    // ---------------- interview ----------------

    /** 本地消息表定时任务分布式锁（interview 侧 LocalMessageRelay） */
    public static final String LOCK_INTERVIEW_LOCAL_MESSAGE = "lock:interview-local-message";
    /** 面试提醒延迟队列 ZSet（D-38） */
    public static final String DELAY_INTERVIEW_REMIND_ZSET = "hirehub:delay:interview-remind";
    /** 面试提醒延迟队列载荷 Hash */
    public static final String DELAY_INTERVIEW_REMIND_PAYLOAD = "hirehub:delay:interview-remind:payload";

    // ---------------- job ----------------

    /** 职位缓存重建互斥锁（JobCacheService，key = 前缀 + 缓存 key） */
    public static final String LOCK_JOB_CACHE_PREFIX = "lock:";
}

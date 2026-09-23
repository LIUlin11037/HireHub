package com.ll.hirehub.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * token 撤销存储（见 D-02）。
 * <p>
 * 为什么需要它：JWT 是无状态的，签发后在过期前永远有效。要支持"登出即失效"，
 * 就必须引入一份<b>可写的状态</b>——这里放在 Redis，用最小代价换撤销能力：
 * <ul>
 *   <li>refresh token：<b>白名单</b>。key 存在才有效；轮换时删旧 jti，登出时清空该用户全部 jti。</li>
 *   <li>access token：<b>黑名单</b>。登出时把 jti 写进去，TTL = 该 token 剩余有效期
 *       （过期后黑名单条目自动消失，不会无限堆积）。</li>
 * </ul>
 * 网关每次请求查一次黑名单，见 {@code AuthGlobalFilter}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenStore {

    private static final String REFRESH_KEY = "auth:refresh:%d:%s";
    private static final String REFRESH_INDEX_KEY = "auth:refresh:index:%d";
    private static final String BLACKLIST_KEY = "auth:blacklist:%s";

    private final StringRedisTemplate redis;

    /** 记录一次 refresh token 签发（白名单） */
    public void storeRefresh(Long userId, String jti, long ttlSeconds) {
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        redis.opsForValue().set(String.format(REFRESH_KEY, userId, jti), "1", ttl);
        String indexKey = String.format(REFRESH_INDEX_KEY, userId);
        redis.opsForSet().add(indexKey, jti);
        // 索引与最长寿命的 token 同寿，避免索引永远不过期
        redis.expire(indexKey, ttl);
    }

    /** refresh token 是否仍然有效（未被轮换 / 撤销） */
    public boolean isRefreshValid(Long userId, String jti) {
        return Boolean.TRUE.equals(redis.hasKey(String.format(REFRESH_KEY, userId, jti)));
    }

    /** 轮换：旧 refresh token 立即作废（一次性使用，防重放） */
    public void revokeRefresh(Long userId, String jti) {
        redis.delete(String.format(REFRESH_KEY, userId, jti));
        redis.opsForSet().remove(String.format(REFRESH_INDEX_KEY, userId), jti);
    }

    /**
     * 撤销该用户全部 refresh token。
     * 使用场景：检测到 refresh token 重放 → 认为凭证可能已泄露，整条链全部失效。
     */
    public void revokeAllRefresh(Long userId) {
        String indexKey = String.format(REFRESH_INDEX_KEY, userId);
        Set<String> jtis = redis.opsForSet().members(indexKey);
        if (jtis != null) {
            // 逐个删白名单 key；单用户并发登录数很小，不值得用 pipeline 优化
            jtis.forEach(jti -> redis.delete(String.format(REFRESH_KEY, userId, jti)));
        }
        redis.delete(indexKey);
    }

    /** access token 拉黑（登出）：TTL 取剩余有效期，过期自动清理 */
    public void blacklistAccess(String jti, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return; // 已过期的 token 本来就会被签名校验拦下，不必写黑名单
        }
        redis.opsForValue().set(String.format(BLACKLIST_KEY, jti), "1", Duration.ofSeconds(ttlSeconds));
    }

    public boolean isAccessBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(String.format(BLACKLIST_KEY, jti)));
    }
}

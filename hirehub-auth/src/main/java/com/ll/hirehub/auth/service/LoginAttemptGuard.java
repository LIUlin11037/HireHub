package com.ll.hirehub.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 登录失败限制（防撞库 / 防暴力破解）。
 * <p>
 * <b>为什么网关限流替代不了它</b>：网关的 Sentinel 流控挡的是"每秒请求数"，
 * 而撞库的典型打法是**慢速、分布式**——每秒一两次，永远不触发阈值，但一天能试几万次。
 * 只有**按账号**计数才拦得住。
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>每次失败 `INCR auth:login:fail:{username}`，并刷新 TTL；</li>
 *   <li>累计达到 {@code hirehub.auth.login.max-fail}（默认 5）→ 写
 *       `auth:login:lock:{username}`（默认锁 15 分钟）+ 清掉计数；</li>
 *   <li>锁定期内**即使密码正确也拒绝** —— 否则爆破方只要在锁定窗口里撞对一次就绕过了一切；</li>
 *   <li>登录成功后清零。</li>
 * </ul>
 * <b>为什么用户名不存在也计数</b>：否则"用户名是否存在"会通过响应差异泄漏出去。
 * 计数 key 用的是**用户输入的用户名**，所以不依赖账号是否真实存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptGuard {

    private static final String FAIL_KEY = "auth:login:fail:";
    private static final String LOCK_KEY = "auth:login:lock:";

    private final StringRedisTemplate redis;

    /** 连续失败多少次后锁定 */
    @Value("${hirehub.auth.login.max-fail:5}")
    private int maxFail;

    /** 计数与锁定的时长（秒）。默认 15 分钟 */
    @Value("${hirehub.auth.login.lock-seconds:900}")
    private long lockSeconds;

    /** 剩余锁定秒数；0 表示未锁定 */
    public long lockedSeconds(String username) {
        if (username == null || username.isBlank()) {
            return 0L;
        }
        Long ttl = redis.getExpire(LOCK_KEY + username, TimeUnit.SECONDS);
        return (ttl != null && ttl > 0) ? ttl : 0L;
    }

    /**
     * 记一次失败。
     *
     * @return true 表示本次失败触发了锁定
     */
    public boolean recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        String key = FAIL_KEY + username;
        Long fails = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofSeconds(lockSeconds));
        if (fails != null && fails >= maxFail) {
            redis.opsForValue().set(LOCK_KEY + username, "1", Duration.ofSeconds(lockSeconds));
            redis.delete(key);   // 清零计数，解锁后重新开始
            log.warn("[登录防护] 连续失败 {} 次，锁定账号 {} 秒: username={}", fails, lockSeconds, username);
            return true;
        }
        return false;
    }

    /** 登录成功：清零计数并解除锁定 */
    public void clear(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        redis.delete(FAIL_KEY + username);
        redis.delete(LOCK_KEY + username);
    }

    /** 把剩余秒数说成人话（向上取整到分钟，最小 1 分钟） */
    public static String describeLock(long seconds) {
        long minutes = Math.max(1, (seconds + 59) / 60);
        return "登录失败次数过多，账号已锁定，请 " + minutes + " 分钟后再试";
    }
}

package com.ll.hirehub.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具：签发 / 解析 access token 与 refresh token（无状态凭证 + Redis 撤销，见 D-02）。
 * <p>
 * 双 token 设计（D-02）：
 * <ul>
 *   <li><b>accessToken</b>：30min，带 {@code roles}，每次请求携带；撤销靠 Redis 黑名单（jti）。</li>
 *   <li><b>refreshToken</b>：7d，<b>不带 roles</b>（减少泄露面），只用于换新 access；存 Redis 可撤销、一次性轮换。</li>
 * </ul>
 * 两者用 {@code typ} claim 区分，避免 refresh token 被当成 access token 使用。
 * 注意：secret 需 ≥ 32 字节（HS256 最低要求），通过配置注入。
 */
@Slf4j
@Component
public class JwtUtil {

    /** token 类型：访问令牌 */
    public static final String TYP_ACCESS = "access";
    /** token 类型：刷新令牌 */
    public static final String TYP_REFRESH = "refresh";

    private static final String CLAIM_TYP = "typ";

    /** 仓库内置的开发默认密钥（审计 S-04）：仅本地开发可用，且必须显式开启 allow-dev-defaults */
    static final String DEV_SECRET = "hirehub-dev-secret-please-override-0123456789";

    // 不再给代码级默认值：缺失即启动失败（审计 S-04）。
    // 本地开发的默认值在 application.yml 里，需配合 hirehub.security.allow-dev-defaults=true 使用。
    @Value("${hirehub.jwt.secret:}")
    private String secret;

    @Value("${hirehub.security.allow-dev-defaults:false}")
    private boolean allowDevDefaults;

    @Value("${hirehub.jwt.access-expire-seconds:1800}")
    private long accessExpireSeconds;

    @Value("${hirehub.jwt.refresh-expire-seconds:604800}")
    private long refreshExpireSeconds;

    /** 启动期校验（审计 S-04）：密钥缺失/过短/生产误用开发默认值 → fail-fast，绝不通关 */
    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "hirehub.jwt.secret 未配置：JWT 签名密钥必须通过 HIREHUB_JWT_SECRET 注入");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("hirehub.jwt.secret 长度不足 32 字节（HS256 最低要求）");
        }
        if (DEV_SECRET.equals(secret)) {
            if (!allowDevDefaults) {
                throw new IllegalStateException(
                        "正在使用仓库内置的开发默认 JWT 密钥：生产环境必须注入 HIREHUB_JWT_SECRET；"
                                + "本地开发请显式设置 hirehub.security.allow-dev-defaults=true");
            }
            log.warn("正在使用开发默认 JWT 密钥（allow-dev-defaults=true），仅限本地开发，严禁生产使用");
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 access token：subject = userId，claims 携带 roles，id = jti */
    public String generateAccessToken(Long userId, List<String> roles, String jti) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("roles", roles)
                .claim(CLAIM_TYP, TYP_ACCESS)
                .id(jti)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpireSeconds * 1000))
                .signWith(key())
                .compact();
    }

    /**
     * 签发 refresh token：subject = userId，<b>不带 roles</b>。
     * 是否可用由 Redis 决定（服务端可撤销），JWT 本身只承载"是谁 + 哪一次签发"。
     */
    public String generateRefreshToken(Long userId, String jti) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYP, TYP_REFRESH)
                .id(jti)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpireSeconds * 1000))
                .signWith(key())
                .compact();
    }

    /** 解析并校验签名与有效期，返回 Claims（失败抛 JwtException） */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 判断 token 类型。历史签发的 token 没有 {@code typ} claim，
     * 一律按 access 处理（保证平滑升级，不需要强制所有人重新登录）。
     */
    public boolean isAccessToken(Claims claims) {
        Object typ = claims.get(CLAIM_TYP);
        return typ == null || TYP_ACCESS.equals(String.valueOf(typ));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYP_REFRESH.equals(String.valueOf(claims.get(CLAIM_TYP)));
    }

    /** token 剩余有效秒数（用于黑名单 TTL，避免 Redis key 无限堆积） */
    public long getRemainingSeconds(Claims claims) {
        Date exp = claims.getExpiration();
        if (exp == null) {
            return 0L;
        }
        long remaining = (exp.getTime() - System.currentTimeMillis()) / 1000;
        return Math.max(remaining, 0L);
    }

    public long getAccessExpireSeconds() {
        return accessExpireSeconds;
    }

    public long getRefreshExpireSeconds() {
        return refreshExpireSeconds;
    }
}

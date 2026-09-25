package com.ll.hirehub.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtil（D-02 双 token）。
 * <p>
 * 这里钉的是三条**安全约定**，每一条错了都不会有编译错误、只会静默失去保护：
 * refresh token 不得携带 roles、签名/有效期必须真的校验、以及历史无 typ 的 token 按 access 处理的平滑升级规则。
 */
@SuppressWarnings("unchecked")
class JwtUtilTest {

    /** HS256 要求密钥不短于 256 bit（32 字节） */
    private static final String SECRET = "hirehub-unit-test-secret-0123456789abcdef";

    private JwtUtil jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", SECRET);
        ReflectionTestUtils.setField(jwt, "accessExpireSeconds", 1800L);
        ReflectionTestUtils.setField(jwt, "refreshExpireSeconds", 604800L);
    }

    @Test
    @DisplayName("access token：subject=userId、带 roles、typ=access、保留 jti")
    void accessTokenCarriesRolesAndJti() {
        String token = jwt.generateAccessToken(42L, List.of("SEEKER", "PLATFORM_ADMIN"), "jti-1");
        Claims claims = jwt.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("roles", List.class)).containsExactly("SEEKER", "PLATFORM_ADMIN");
        assertThat(claims.getId()).isEqualTo("jti-1");
        assertThat(jwt.isAccessToken(claims)).isTrue();
        assertThat(jwt.isRefreshToken(claims)).isFalse();
    }

    @Test
    @DisplayName("★ refresh token 绝不携带 roles（否则刷新出来的凭证能自己升级权限）")
    void refreshTokenCarriesNoRoles() {
        String token = jwt.generateRefreshToken(42L, "jti-2");
        Claims claims = jwt.parseToken(token);

        assertThat(claims.get("roles")).isNull();
        assertThat(jwt.isRefreshToken(claims)).isTrue();
        assertThat(jwt.isAccessToken(claims)).isFalse();
    }

    @Test
    @DisplayName("★ 篡改过的 token 必须解析失败（签名校验真的在生效）")
    void tamperedTokenIsRejected() {
        String token = jwt.generateAccessToken(42L, List.of("SEEKER"), "jti-3");
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThatThrownBy(() -> jwt.parseToken(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("★ 换一个密钥签发的 token 必须被拒（防止共享/默认密钥被误用）")
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreign = Jwts.builder()
                .subject("42")
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-0123456789".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwt.parseToken(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("过期 token 必须解析失败")
    void expiredTokenIsRejected() {
        ReflectionTestUtils.setField(jwt, "accessExpireSeconds", -10L);
        String expired = jwt.generateAccessToken(42L, List.of("SEEKER"), "jti-4");

        assertThatThrownBy(() -> jwt.parseToken(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("getRemainingSeconds 用于黑名单 TTL：新 token 应接近 accessExpireSeconds，过期则归零")
    void remainingSeconds() {
        String token = jwt.generateAccessToken(42L, List.of("SEEKER"), "jti-5");
        long remaining = jwt.getRemainingSeconds(jwt.parseToken(token));
        assertThat(remaining).isBetween(1700L, 1800L);

        // 过期 token 是**解析不出来**的（上面那条用例正好证明了这点），
        // 所以这里直接构造一个已过期的 Claims，专测 getRemainingSeconds 的"不返回负数"约定。
        Claims expired = Jwts.claims()
                .expiration(new Date(System.currentTimeMillis() - 5000))
                .build();
        assertThat(jwt.getRemainingSeconds(expired)).isZero();

        // 没有 exp 的 claims 也不能抛异常
        assertThat(jwt.getRemainingSeconds(Jwts.claims().build())).isZero();
    }

    /**
     * 平滑升级约定：历史签发的 token 没有 typ，必须按 access 处理，
     * 否则上线那一刻所有已登录用户会被强制踢下线。
     */
    @Test
    @DisplayName("兼容性：无 typ 的历史 token 按 access 处理")
    void legacyTokenWithoutTypCountsAsAccess() {
        String legacy = Jwts.builder()
                .subject("42")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        Claims claims = jwt.parseToken(legacy);
        assertThat(jwt.isAccessToken(claims)).isTrue();
        assertThat(jwt.isRefreshToken(claims)).isFalse();
    }
}

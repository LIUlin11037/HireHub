package com.ll.hirehub.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具：签发 / 解析 access token（无状态）
 * 注意：secret 需 ≥ 32 字节（HS256 最低要求），通过配置注入
 */
@Component
public class JwtUtil {

    // 默认仅用于本地开发；生产必须通过环境变量覆盖（见架构文档 D-02 / D-18）
    @Value("${hirehub.jwt.secret:hirehub-dev-secret-please-override-0123456789}")
    private String secret;

    @Value("${hirehub.jwt.access-expire-seconds:1800}")
    private long accessExpireSeconds;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 access token：subject = userId，claims 携带 roles，id = jti */
    public String generateAccessToken(Long userId, List<String> roles, String jti) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("roles", roles)
                .id(jti)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpireSeconds * 1000))
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

    public long getAccessExpireSeconds() {
        return accessExpireSeconds;
    }
}

package com.hotel.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：签发 / 解析 Token
 */
@Component
public class JwtUtil {

    /** HS256 要求密钥至少 32 字节 */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire}")
    private Long expire;

    private SecretKey key;

    @PostConstruct
    public void init() {
        // 启动期校验：缺少密钥时直接失败，避免用空密钥或默认值签发可被伪造的令牌
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("未配置 JWT 签名密钥，请设置环境变量 JWT_SECRET");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT 签名密钥长度不足 " + MIN_SECRET_BYTES + " 字节，请更换更强的密钥");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** 签发 Token：subject=userId，附带 username / role */
    public String createToken(Long userId, String username, Integer role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expire * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    /** 解析并校验 Token（过期或签名错误会抛 JwtException） */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Token 有效期（秒） */
    public Long getExpire() {
        return expire;
    }
}

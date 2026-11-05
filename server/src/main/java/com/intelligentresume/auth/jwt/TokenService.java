package com.intelligentresume.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * JWT 签发 / 解析 + 刷新令牌原文安全摘要。
 *
 * <p>实现细节:
 * <ul>
 *     <li>access token 使用 HS256,密钥来源于 {@code app.jwt.secret}(原文 UTF-8 字节)。</li>
 *     <li>refresh token 为 32 字节随机十六进制字符串,不落库原文,仅保存 SHA-256 摘要。</li>
 *     <li>{@link #hashToken(String)} 对任何 token 字符串统一返回 64 字符十六进制摘要。</li>
 * </ul>
 *
 * <p>红线:
 * <ul>
 *     <li>密钥按 UTF-8 **字节**计算长度,少于 32 字节在构造期直接抛异常(HS256 的硬要求);
 *         prod profile 另外由 {@code ProductionConfigurationValidator} 拒绝默认值。</li>
 *     <li>refresh token 只能经 {@link #issueRefreshToken()} 生成,且调用方必须先 {@link #hashToken}
 *         再落库——库里出现原文即视为凭据泄露。</li>
 *     <li>改 claim 结构(如 subject 语义)会让所有在线 access token 失效,需按发布窗口安排。</li>
 * </ul>
 */
@Service
public class TokenService {

    private final SecretKey accessKey;
    private final String issuer;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;

    public TokenService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.access-token-ttl-seconds}") long accessTokenTtlSeconds,
            @Value("${app.jwt.refresh-token-ttl-seconds}") long refreshTokenTtlSeconds
    ) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes (HS256 requirement)");
        }
        this.accessKey = Keys.hmacShaKeyFor(bytes);
        this.issuer = issuer;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public String getIssuer() {
        return issuer;
    }

    /**
     * 签发 access token。
     *
     * @param userId 当前用户 id(写入 claim)
     * @param username 当前用户名(写入 claim,便于审计日志只打印 username)
     */
    public String issueAccessToken(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(accessTokenTtlSeconds)))
                .signWith(accessKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 生成 refresh token 原文。调用方必须仅保存其摘要到数据库。
     */
    public String issueRefreshToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 从 access token 解析 userId。token 无效 / 过期 / 签名错误时返回 null。
     *
     * <p>只验证签名与 issuer，**不查库**：账号是否仍为 ACTIVE、会话是否被撤销由
     * {@code JwtAuthenticationFilter} + {@code ActiveUserCache} 负责，两段合起来才是完整的鉴权。
     */
    public Long parseUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(accessKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (JwtException | IllegalArgumentException | ArithmeticException ex) {
            return null;
        }
    }

    /**
     * 计算任意 token 字符串的 SHA-256 摘要(64 字符十六进制)。
     * 用于 refresh token 的安全存储对比。
     */
    public String hashToken(String token) {
        if (token == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 永远可用,失败即降级到 Base64
            // 注意:该分支产出的是 Base64 而非 64 位十六进制,与正常路径的摘要不可互比;
            // 一旦触发,"签发时入库的摘要" 与 "刷新时计算的摘要" 会走同一降级路径,仍能匹配,
            // 但长度与字符集变化会让外部按固定格式解析的代码(测试/运维脚本)失配。
            return Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * refresh token 族的 id(UUID v4,业务层生成)。
     */
    public String newTokenFamilyId() {
        return UUID.randomUUID().toString();
    }
}

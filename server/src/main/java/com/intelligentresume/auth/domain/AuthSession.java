package com.intelligentresume.auth.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 刷新令牌会话。
 *
 * <p>字段名与 V1 DDL 完全一致；这里仅作为骨架占位。
 *
 * <p>不可破坏的语义：
 * <ul>
 *   <li>{@code refresh_token_hash} 存 SHA-256 摘要，原文只在签发那一刻存在于内存/ Cookie；</li>
 *   <li>{@code token_family_id} 标识一次登录派生出的整串轮换链，检测到重放时整族撤销；</li>
 *   <li>{@code revoked_at} 非空即失效，{@code revoke_reason} 是取证信息（rotated / logout /
 *       refresh_reuse_detected / expired），不要在复用分支覆盖已有原因。</li>
 * </ul>
 */
@Entity
@Table(name = "auth_session")
public class AuthSession extends BaseEntity {

    /** 会话所属用户。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 令牌族 id：一次登录一个族，轮换产生的每个会话共享它。 */
    @Column(name = "token_family_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String tokenFamilyId;

    /** refresh token 的 SHA-256 十六进制摘要（绝不存原文）。 */
    @Column(name = "refresh_token_hash", nullable = false, length = 255)
    private String refreshTokenHash;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 撤销时间；非空即失效。撤销是单向的，恢复只能重新登录换新会话。 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 撤销原因（审计用），长度 64，取值见 AuthService / AuthSessionRevocationService 的常量字符串。 */
    @Column(name = "revoke_reason", length = 64)
    private String revokeReason;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTokenFamilyId() { return tokenFamilyId; }
    public void setTokenFamilyId(String tokenFamilyId) { this.tokenFamilyId = tokenFamilyId; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public void setRefreshTokenHash(String refreshTokenHash) { this.refreshTokenHash = refreshTokenHash; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
}

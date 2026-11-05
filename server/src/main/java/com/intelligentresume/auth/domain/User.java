package com.intelligentresume.auth.domain;

import com.intelligentresume.common.persistence.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * 账号实体（表 {@code user}）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>继承 {@link SoftDeletableEntity}，因此所有 JPA 查询自动带 {@code deleted_at IS NULL}——
 *       删号后的用户对 {@code findByUsername/findById} 不可见（撤销/清理必须走原生 SQL，见 retention 包）；</li>
 *   <li>{@code passwordHash} 为 BCrypt 串，任何日志/DTO/导出都不得包含它；</li>
 *   <li>{@code status} 用 {@code @Enumerated(STRING)} 存 ACTIVE/DISABLED，删号只置 DISABLED 而不是依赖
 *       {@code deletedAt} 做鉴权判断（{@link com.intelligentresume.auth.service.ActiveUserCache} 只看 status）。</li>
 * </ul>
 */
@Entity
@Table(name = "user")
public class User extends SoftDeletableEntity {

    /** 登录名，注册时限制为 {@code [a-zA-Z0-9_.-]}，长度 3~64。 */
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** 邮箱；登录时也可作为 username 传入，改邮箱前须校验当前密码。 */
    @Column(name = "email", nullable = false, length = 128)
    private String email;

    /** BCrypt 散列，不存明文；登录失败时不要按字段分别报错（防账号枚举）。 */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 展示名，注册时默认取 username，可在个人资料里修改。 */
    @Column(name = "display_name", length = 128)
    private String displayName;

    /** 账号状态；删号（进入撤销窗口）时置 DISABLED，恢复时置回 ACTIVE。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

    /** 账号状态：ACTIVE 可登录；DISABLED 被停用或处于删号撤销期（登录会返回 40303/40301）。 */
    public enum UserStatus {
        ACTIVE,
        DISABLED
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
}
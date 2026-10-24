package com.intelligentresume.ai.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 桌面端本地 AI 设置（V38）——**只有非敏感部分**。
 *
 * <p>为什么没有密钥列：ADR-012 D8 要求凭据用 DPAPI 加密存在数据目录的独立文件里，
 * R34 又明确「凭据不得进备份 / Web 导出包 / 桌面迁移包」。备份是整库 {@code SCRIPT TO}，
 * 密钥一旦入表就必然被打包带走。{@code credentialPresent} 只表示「本机是否保存过密钥」，
 * 供界面提示「恢复工作区后需要重新配置」（密文本就不随工作区迁移）。
 */
@Entity
@Table(name = "local_ai_setting")
public class LocalAiSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "provider_code", nullable = false, length = 32)
    private String providerCode;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "credential_present", nullable = false)
    private boolean credentialPresent;

    @Column(name = "last_tested_at")
    private LocalDateTime lastTestedAt;

    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "last_test_message", length = 512)
    private String lastTestMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCredentialPresent() {
        return credentialPresent;
    }

    public void setCredentialPresent(boolean credentialPresent) {
        this.credentialPresent = credentialPresent;
    }

    public LocalDateTime getLastTestedAt() {
        return lastTestedAt;
    }

    public void setLastTestedAt(LocalDateTime lastTestedAt) {
        this.lastTestedAt = lastTestedAt;
    }

    public Boolean getLastTestOk() {
        return lastTestOk;
    }

    public void setLastTestOk(Boolean lastTestOk) {
        this.lastTestOk = lastTestOk;
    }

    public String getLastTestMessage() {
        return lastTestMessage;
    }

    public void setLastTestMessage(String lastTestMessage) {
        this.lastTestMessage = lastTestMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

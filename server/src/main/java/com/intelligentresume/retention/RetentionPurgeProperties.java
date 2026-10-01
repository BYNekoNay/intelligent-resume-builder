package com.intelligentresume.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据生命周期分档清扫配置（决策 D2 阶段 1）。
 *
 * <p>设计见 {@code docs/plans/2026-10-01-001-data-retention-tiered-purge.md}。
 * 两条**默认安全**的护栏在此固化：
 * <ul>
 *   <li>{@code enabled} 默认 <b>false</b> —— 未显式开启时作业不做任何写操作；</li>
 *   <li>{@code dryRun} 默认 <b>true</b> —— 即使开启也只记「计划动作」，必须显式置 false 才真正删除。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.retention.purge")
public class RetentionPurgeProperties {

    /** 总开关。默认关闭 —— 避免「部署即开始删数据」。 */
    private boolean enabled = false;

    /** 试运行。默认开启 —— 即使总开关打开，也只记日志不写库。 */
    private boolean dryRun = true;

    /** 单轮每张表最多处理多少行（防止一次扫全表）。 */
    private int batchSize = 100;

    /** 软删后的恢复期（天）；期满才开始考虑硬删。与 docs/04 §7.1 的承诺一致。 */
    private int recoveryDays = 30;

    /** 恢复期后的宽限期（天）；与 docs/04 §7.1「恢复期结束后 7 天内完成硬删」一致。 */
    private int graceDays = 7;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getRecoveryDays() { return recoveryDays; }
    public void setRecoveryDays(int recoveryDays) { this.recoveryDays = recoveryDays; }

    public int getGraceDays() { return graceDays; }
    public void setGraceDays(int graceDays) { this.graceDays = graceDays; }

    /** 候选行的截止时间 = now - (recoveryDays + graceDays)。 */
    public java.time.LocalDateTime cutoffFrom(java.time.LocalDateTime now) {
        return now.minusDays((long) recoveryDays + graceDays);
    }
}

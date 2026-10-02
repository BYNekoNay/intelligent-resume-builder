package com.intelligentresume.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 账户清扫作业配置（决策 D2 阶段 3）。
 *
 * <p>两条默认安全护栏与 {@link RetentionPurgeProperties} 同风格：
 * {@code enabled} 默认 <b>false</b>（部署不即删）；语句幂等使 {@code PARTIAL_FAILED} 可重入。
 * 撤销窗口天数（{@code app.retention.account-deletion.grace-days}，默认 7）由
 * {@code AuthService} 的标量 {@code @Value} 消费 —— 见 ADR-008 的标量/绑定分工。
 */
@Component
@ConfigurationProperties(prefix = "app.retention.account-purge")
public class AccountPurgeProperties {

    /** 总开关，默认关闭 —— 与分档清扫同一护栏口径。 */
    private boolean enabled = false;

    /** 单轮最多处理多少个到期任务（每个任务 = 一个用户的全量级联删除，重操作）。 */
    private int batchSize = 20;

    /** 调度间隔（毫秒），默认 6 小时 —— 「窗口结束后 30 天内完成清理」的承诺下，远早于上限。 */
    private long intervalMs = 21_600_000L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
}

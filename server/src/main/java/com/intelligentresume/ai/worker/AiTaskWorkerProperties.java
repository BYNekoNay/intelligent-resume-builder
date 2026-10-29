package com.intelligentresume.ai.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 工作器配置属性。绑定 {@code app.ai.worker.*}。
 */
@Component
@ConfigurationProperties(prefix = "app.ai.worker")
public class AiTaskWorkerProperties {

    private long pollIntervalMs = 1000;
    /**
     * 任务租约时长。**必须大于单任务最坏执行时长**（AI 链总预算 600s），否则心跳失联期间
     * 旧 worker 仍可能被接管重跑、重复调用 provider。
     *
     * <p>此默认值必须与 {@code application.yml} 的 {@code app.ai.worker.lease-seconds}
     * （{@code ${AI_WORKER_LEASE_S:660}}）一致：该键在 yml 存在时以 yml 为准，仅当 yml 缺键时
     * 才用本默认值——两处不一致即「配置缺省时租约被静默改成另一个值」的陷阱。
     * 历史上 yml 已从 180 提到 660（见报告第一批 #60），本默认值当时漏改（第五十三批修复）。
     */
    private int leaseSeconds = 660;
    private int maxRetries = 3;
    /**
     * 崩溃循环收口阈值：一个任务**最多被领取几次**就该有结论。
     *
     * <p>刻意与 {@link #maxRetries} 分开：那个是「用户可见的手动重试上限」，
     * 各环境会把它调成 1（测试/桌面/CI 不重试）；如果收口也用同一个值，
     * 那「租约过期被正常接管」在那些环境里会被误判成不可恢复的中断。
     * 两者是不同的问题：一个关于用户能点几次，一个关于进程被杀了几次。
     */
    private int maxAttempts = 3;
    private int batchSize = 5;
    /** 重任务分组（分钟级）的并发执行额度。 */
    private int heavyConcurrency = 2;
    /** 轻任务分组（秒级）的并发执行额度；独立于重任务，保证不被饿死。 */
    private int lightConcurrency = 2;

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getHeavyConcurrency() { return heavyConcurrency; }
    public void setHeavyConcurrency(int heavyConcurrency) { this.heavyConcurrency = heavyConcurrency; }
    public int getLightConcurrency() { return lightConcurrency; }
    public void setLightConcurrency(int lightConcurrency) { this.lightConcurrency = lightConcurrency; }
}

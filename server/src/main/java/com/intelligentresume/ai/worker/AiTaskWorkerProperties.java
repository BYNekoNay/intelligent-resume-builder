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
    private int leaseSeconds = 180;
    private int maxRetries = 3;
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
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getHeavyConcurrency() { return heavyConcurrency; }
    public void setHeavyConcurrency(int heavyConcurrency) { this.heavyConcurrency = heavyConcurrency; }
    public int getLightConcurrency() { return lightConcurrency; }
    public void setLightConcurrency(int lightConcurrency) { this.lightConcurrency = lightConcurrency; }
}

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
    private int leaseSeconds = 60;
    private int maxRetries = 3;
    private int batchSize = 5;

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}

package com.intelligentresume.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 {@code @Scheduled} 异步轮询(T06 工作器、T10 PDF 工作器复用)。
 */
@Configuration
@EnableScheduling
public class WorkerSchedulingConfig {
}
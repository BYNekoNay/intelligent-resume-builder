package com.intelligentresume.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 启用 JPA 审计字段自动填充(created_at / updated_at)。
 *
 * <p>对 {@link com.intelligentresume.common.persistence.BaseEntity} 的审计字段生效。
 */
@Configuration
@EnableJpaAuditing
public class PersistenceConfig {
}
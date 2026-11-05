package com.intelligentresume.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 启用 JPA 审计字段自动填充(created_at / updated_at)。
 *
 * <p>对 {@link com.intelligentresume.common.persistence.BaseEntity} 的审计字段生效。
 *
 * <p>依赖关系：{@code BaseEntity} 上的 {@code @CreatedDate}/{@code @LastModifiedDate} 没有这行注解
 * 就不会被填充，插入时两列 NOT NULL 直接失败。使用 @DataJpaTest 等切片测试时若未加载本配置，
 * 需要手动给实体赋值或自行启用审计。审计时间取自应用时钟而非数据库时钟。
 */
@Configuration
@EnableJpaAuditing
public class PersistenceConfig {
}
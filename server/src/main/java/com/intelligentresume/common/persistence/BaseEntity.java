package com.intelligentresume.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 公共实体基类:id + createdAt + updatedAt。
 *
 * <p>【小白导览】几乎所有表的「公共列模板」：谁建的、什么时候建的、最后一次改动是什么时候
 * （审计字段，自动填充不用手写）。业务实体继承它就自动带上这三列。改坏它 → 插入数据时
 * 时间列为空直接报错（列有 NOT NULL 约束）。
 *
 * <p>不包含 {@code deleted_at}。需要软删的实体继承 {@link SoftDeletableEntity}。
 *
 * <p>使用约束:
 * <ul>
 *   <li>审计字段由 {@code AuditingEntityListener} 写入,前提是 {@code PersistenceConfig} 上的
 *       {@code @EnableJpaAuditing} 生效;两者缺一,插入时会因 NOT NULL 失败。</li>
 *   <li>两个时间字段**没有 setter**:手工 {@code new} 出来、尚未 save 的实体它们是 null,
 *       单测断言时间前必须先持久化。</li>
 *   <li>主键策略是 IDENTITY,id 在 insert/flush 之后才有值;依赖 id 的逻辑不能放在 persist 之前。</li>
 *   <li>并非所有表都适合继承(worker 用原生 SQL 自己维护 {@code updated_at} 的实体刻意不继承)。</li>
 * </ul>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    /** 主键,自增;insert 之后才可用。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 创建时间,仅在 insert 时写一次(updatable=false)。 */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最后修改时间,每次 update 自动刷新;原生 SQL 更新不会触发它。 */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

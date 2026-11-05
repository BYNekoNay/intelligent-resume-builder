package com.intelligentresume.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 软删除基类:在 {@link BaseEntity} 上叠加 {@code deleted_at} 与全局过滤。
 *
 * <p>仅适用于 DDL 中已经存在 {@code deleted_at} 列的表。
 * 由子类在 DDL 设计时主动决定是否继承。
 *
 * <p>必须知道的行为(改动前先读):
 * <ul>
 *   <li>{@code @SQLRestriction} 对**所有 JPA 查询**生效(findById、派生查询、JPQL),已软删的行默认查不到;
 *       需要读取已删行(撤销删号、清理作业、审计)只能走原生 SQL。</li>
 *   <li>原生 SQL / JdbcTemplate 不受该过滤影响,因此 retention 包的语句必须自己写
 *       {@code deleted_at IS NOT NULL} 作为二次保护。</li>
 *   <li>软删只是打标记,不会级联子表;物理删除由 retention 作业按引用关系决定。</li>
 *   <li>过滤条件硬编码了列名 {@code deleted_at},改列名必须同步此注解。</li>
 * </ul>
 */
@MappedSuperclass
@SQLRestriction("deleted_at IS NULL")
public abstract class SoftDeletableEntity extends BaseEntity {

    /** 软删时间;非空即视为已删除,JPA 层从此不可见。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}

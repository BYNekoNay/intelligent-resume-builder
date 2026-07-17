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
 */
@MappedSuperclass
@SQLRestriction("deleted_at IS NULL")
public abstract class SoftDeletableEntity extends BaseEntity {

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}

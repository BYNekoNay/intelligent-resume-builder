package com.intelligentresume.resume.domain;

import com.intelligentresume.common.persistence.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * 简历主表。字段与 V1 DDL {@code resume} 完全一致。
 *
 * <p>软删除：继承 {@link SoftDeletableEntity}，全局过滤 {@code deleted_at IS NULL}。
 */
@Entity
@Table(name = "resume")
@SQLRestriction("deleted_at IS NULL")
public class Resume extends SoftDeletableEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "current_version_id")
    private Long currentVersionId;

    @Column(name = "job_description_id")
    private Long jobDescriptionId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(Long currentVersionId) { this.currentVersionId = currentVersionId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
}

package com.intelligentresume.resume.domain;

import com.intelligentresume.common.persistence.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLRestriction;

/**
 * 简历主表。字段与 V1 DDL {@code resume} 完全一致。
 *
 * <p>职责一句话：只存"这份简历是谁的、叫什么、当前指向哪个版本"，内容全在 {@link ResumeVersion}。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code current_version_id} 可为 null（主档刚建、还没存过任何版本），所有读取方必须容忍。</li>
 *     <li>{@code job_description_id} 只标识"这份简历针对哪个岗位"，不参与权限判断——权限永远看 {@code user_id}。</li>
 *     <li>内容与版式都在 {@link ResumeVersion}，本表不放任何简历正文，避免两处数据不一致。</li>
 * </ul>
 *
 * <p>软删除：继承 {@link SoftDeletableEntity}，全局过滤 {@code deleted_at IS NULL}。
 *
 * <p>乐观锁（#38）：{@code current_version_id} 此前没有版本条件——标题/软删等
 * 其它字段的写入会把陈旧实体里的版本指针一起写回（静默回退）。加 {@code @Version}
 * 后陈旧副本的保存抛乐观锁冲突（40901），不再覆盖并发推进的版本指针。
 */
@Entity
@Table(name = "resume")
@SQLRestriction("deleted_at IS NULL")
public class Resume extends SoftDeletableEntity {

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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

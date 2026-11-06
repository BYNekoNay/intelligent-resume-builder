package com.intelligentresume.application.domain;

import com.intelligentresume.common.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

@Entity
@Table(name = "application_record")
// ⚠ 必须在实体类上重复声明：@SQLRestriction 标在 @MappedSuperclass（SoftDeletableEntity）
// 上**不会被实体继承**（Hibernate 只认直接标注），与 Resume / JobDescription 同款。
// 漏声明的后果：软删行依然出现在列表里（ApplicationControllerIT 实测抓住）。
@SQLRestriction("deleted_at IS NULL")
/**
 * 投递记录（一张投递"跟踪卡"在数据库里的样子：投了哪家 JD、用的哪个简历版本、当前状态、
 * 三段草稿文案、各时间戳）。
 *
 * <p>在链路哪里：{@code ApplicationService} 读写它，前端看板的每张卡片对应这里一行。
 *
 * <p>软删除（V40 起，即"不真删，只打标记"）：{@code extends SoftDeletableEntity} 全局过滤已删行；
 * 清扫作业 {@code RetentionPurgeService} 在恢复期结束后物理删除（唯一引用者
 * {@code application_status_history} 是 ON DELETE CASCADE 的从属内容，随行一起消亡）。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code @SQLRestriction} 必须在实体类上直接再标一次——标在父类上不会被继承。</li>
 *     <li>{@code status} 只能通过状态迁移接口改；普通编辑接口收到不同的 status 会拒绝（保证历史表完整）。</li>
 *     <li>{@code appliedAt} 只在首次进入 APPLIED 时写入，之后不再变动，是"投了多久"的起点。</li>
 *     <li>{@code stageEnteredAt} 只随状态变化刷新；任何其它字段的更新都不得碰它，否则停留时长被静默重置。</li>
 *     <li>{@code @Version} 是乐观锁：并发编辑同一投递会抛冲突，而不是后写覆盖先写。</li>
 * </ul>
 */
public class ApplicationRecord extends SoftDeletableEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "job_description_id", nullable = false)
    private Long jobDescriptionId;
    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ApplicationStatus status;
    @Column(name = "cover_letter_text", columnDefinition = "TEXT")
    private String coverLetterText;
    @Column(name = "email_body_text", columnDefinition = "TEXT")
    private String emailBodyText;
    @Column(name = "opening_message_text", columnDefinition = "TEXT")
    private String openingMessageText;
    @Column(name = "feedback_text", columnDefinition = "TEXT")
    private String feedbackText;
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
    /** 下次跟进提醒时间；为空表示不提醒。列表的 TODAY/OVERDUE 过滤依赖该列。 */
    @Column(name = "next_follow_up_at")
    private LocalDateTime nextFollowUpAt;
    /**
     * 当前状态进入时刻（仅在状态实际发生迁移时刷新，见 ApplicationService）。
     *
     * <p>修复模块核实报告 P1-4：投递看板 avgStageDurationDays.interviewing 原用
     * updatedAt 充当"进入面试时间"，但 updatedAt 在任何字段更新（改 follow-up、
     * 补 feedback、PUT 编辑）都会刷新，导致面试停留时长被静默重置。改用本字段，
     * status 未变化的更新不得刷新。
     */
    @Column(name = "stage_entered_at")
    private LocalDateTime stageEnteredAt;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }
    public String getCoverLetterText() { return coverLetterText; }
    public void setCoverLetterText(String coverLetterText) { this.coverLetterText = coverLetterText; }
    public String getEmailBodyText() { return emailBodyText; }
    public void setEmailBodyText(String emailBodyText) { this.emailBodyText = emailBodyText; }
    public String getOpeningMessageText() { return openingMessageText; }
    public void setOpeningMessageText(String openingMessageText) { this.openingMessageText = openingMessageText; }
    public String getFeedbackText() { return feedbackText; }
    public void setFeedbackText(String feedbackText) { this.feedbackText = feedbackText; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(LocalDateTime appliedAt) { this.appliedAt = appliedAt; }
    public LocalDateTime getNextFollowUpAt() { return nextFollowUpAt; }
    public void setNextFollowUpAt(LocalDateTime nextFollowUpAt) { this.nextFollowUpAt = nextFollowUpAt; }
    public LocalDateTime getStageEnteredAt() { return stageEnteredAt; }
    public void setStageEnteredAt(LocalDateTime stageEnteredAt) { this.stageEnteredAt = stageEnteredAt; }
    public Long getVersion() { return version; }
}

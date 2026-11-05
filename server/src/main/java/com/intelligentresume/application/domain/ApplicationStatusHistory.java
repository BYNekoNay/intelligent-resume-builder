package com.intelligentresume.application.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Immutable audit entry for one application status value.
 *
 * <p>投递状态历史的不可变审计行：每次**真实**状态迁移写一条，只增不改。
 *
 * <p>要点：
 * <ul>
 *     <li>只记录迁移，不记录"保存了但没有变化"的提交；否则时间线会被噪声淹没。</li>
 *     <li>{@code fromStatus} 可空（首条记录没有前态），{@code toStatus} 必填。</li>
 *     <li>被投递记录级联删除（{@code ON DELETE CASCADE}），不单独留存。</li>
 *     <li>索引 {@code (application_record_id, changed_at)} 服务于"按时间读某个投递的历史"。</li>
 * </ul>
 */
@Entity
@Table(name = "application_status_history", indexes = {
        @Index(name = "idx_application_status_history_application_changed", columnList = "application_record_id, changed_at")
})
public class ApplicationStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_record_id", nullable = false)
    private Long applicationRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private ApplicationStatus toStatus;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    public Long getId() { return id; }
    public Long getApplicationRecordId() { return applicationRecordId; }
    public void setApplicationRecordId(Long applicationRecordId) { this.applicationRecordId = applicationRecordId; }
    public ApplicationStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(ApplicationStatus fromStatus) { this.fromStatus = fromStatus; }
    public ApplicationStatus getToStatus() { return toStatus; }
    public void setToStatus(ApplicationStatus toStatus) { this.toStatus = toStatus; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }
}

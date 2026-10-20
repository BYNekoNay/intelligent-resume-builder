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

/** Immutable audit entry for one application status value. */
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

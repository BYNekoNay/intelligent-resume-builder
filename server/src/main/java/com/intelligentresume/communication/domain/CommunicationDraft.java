package com.intelligentresume.communication.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "communication_draft")
public class CommunicationDraft extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "resume_version_id", nullable = false) private Long resumeVersionId;
    @Column(name = "job_description_id", nullable = false) private Long jobDescriptionId;
    @Enumerated(EnumType.STRING) @Column(name = "draft_type", nullable = false, length = 32) private CommunicationType type;
    @Column(name = "draft_text", nullable = false, columnDefinition = "TEXT") private String draftText;
    public void setUserId(Long value) { userId = value; }
    public void setResumeVersionId(Long value) { resumeVersionId = value; }
    public void setJobDescriptionId(Long value) { jobDescriptionId = value; }
    public void setType(CommunicationType value) { type = value; }
    public void setDraftText(String value) { draftText = value; }
}

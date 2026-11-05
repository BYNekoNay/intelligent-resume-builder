package com.intelligentresume.communication.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.*;

/**
 * 沟通草稿（表 {@code communication_draft}）：用户确认保存的文案正文，**不表示已发送**。
 *
 * <p>要点：
 * <ul>
 *   <li>五个业务字段全部 NOT NULL：草稿必须挂在「用户 + 简历版本 + JD + 类型」四元组上，否则无法溯源。</li>
 *   <li>{@code type} 以字符串落库（{@link CommunicationType}），新增枚举值无需改 DDL，但改名字会读不出来。</li>
 *   <li>AI 落库前会按「用户/版本/JD/类型/正文」查重，同内容不重复插入（见 {@code CommunicationAiService#persistDraft}）。</li>
 *   <li>当前只有「保存」接口（无更新/删除），因此本表实际是只追加；也没有乐观锁字段。</li>
 * </ul>
 */
@Entity
@Table(name = "communication_draft")
public class CommunicationDraft extends BaseEntity {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "resume_version_id", nullable = false) private Long resumeVersionId;
    @Column(name = "job_description_id", nullable = false) private Long jobDescriptionId;
    @Enumerated(EnumType.STRING) @Column(name = "draft_type", nullable = false, length = 32) private CommunicationType type;
    @Column(name = "draft_text", nullable = false, columnDefinition = "TEXT") private String draftText;
    public Long getUserId() { return userId; }
    public Long getResumeVersionId() { return resumeVersionId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public CommunicationType getType() { return type; }
    public String getDraftText() { return draftText; }
    public void setUserId(Long value) { userId = value; }
    public void setResumeVersionId(Long value) { resumeVersionId = value; }
    public void setJobDescriptionId(Long value) { jobDescriptionId = value; }
    public void setType(CommunicationType value) { type = value; }
    public void setDraftText(String value) { draftText = value; }
}

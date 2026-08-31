package com.intelligentresume.interview.asset.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 面试答案资产 <-> 简历章节/素材关联。
 * 每个资产可关联多个章节，同一章节下可关联多个素材条目。
 */
@Entity
@Table(name = "interview_asset_section")
public class InterviewAssetSection extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "section_key", nullable = false, length = 32)
    private String sectionKey;

    @Column(name = "material_id")
    private Long materialId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getSectionKey() { return sectionKey; }
    public void setSectionKey(String sectionKey) { this.sectionKey = sectionKey; }
    public Long getMaterialId() { return materialId; }
    public void setMaterialId(Long materialId) { this.materialId = materialId; }
}

package com.intelligentresume.interview.asset.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 面试答案资产 <-> 简历章节/素材关联。
 * 每个资产可关联多个章节，同一章节下可关联多个素材条目。
 *
 * <p>要点：
 * <ul>
 *   <li>一行代表一个「章节 × 素材」组合；{@code sectionKey} 与 {@code materialId} 都允许为空
 *       （仅挂素材、或仅挂章节），但不会两者同时为空。</li>
 *   <li>{@code sectionKey} 必须是资产服务里的章节白名单之一，否则前端渲染不出来。</li>
 *   <li>没有软删除：资产删除时随删，因此清扫作业可以据「无引用」判定资产是否可物理删除。</li>
 *   <li>按章节反查资产时用 {@code userId + sectionKey}，不要只按 sectionKey（跨用户）。</li>
 * </ul>
 */
@Entity
@Table(name = "interview_asset_section")
public class InterviewAssetSection extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "section_key", length = 32)
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

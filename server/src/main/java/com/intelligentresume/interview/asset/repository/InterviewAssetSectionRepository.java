package com.intelligentresume.interview.asset.repository;

import com.intelligentresume.interview.asset.domain.InterviewAssetSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 资产与章节/素材的关联查询。
 *
 * <p>【小白导读】一句话：素材卡「挂到哪些章节/素材」的关系表（interview_asset_section）读入口；
 * 列表页一次批量取回、按资产分组，避免每张卡再查一次数据库。
 *
 * <p>要点：
 * <ul>
 *   <li>列表场景用 {@code findByAssetIdIn} 一次批量取回再按 assetId 分组，避免 N+1。</li>
 *   <li>{@code deleteByAssetId} 是物理删除（本表无软删除），删除后资产可能变成无引用状态。</li>
 *   <li>按章节反查必须同时带 userId。</li>
 * </ul>
 */
public interface InterviewAssetSectionRepository extends JpaRepository<InterviewAssetSection, Long> {

    List<InterviewAssetSection> findByAssetId(Long assetId);

    List<InterviewAssetSection> findByAssetIdIn(Collection<Long> assetIds);

    List<InterviewAssetSection> findByUserIdAndSectionKeyOrderByUpdatedAtDesc(Long userId, String sectionKey);

    void deleteByAssetId(Long assetId);
}

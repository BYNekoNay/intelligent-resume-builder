package com.intelligentresume.interview.asset.repository;

import com.intelligentresume.interview.asset.domain.InterviewAssetSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface InterviewAssetSectionRepository extends JpaRepository<InterviewAssetSection, Long> {

    List<InterviewAssetSection> findByAssetId(Long assetId);

    List<InterviewAssetSection> findByAssetIdIn(Collection<Long> assetIds);

    List<InterviewAssetSection> findByUserIdAndSectionKeyOrderByUpdatedAtDesc(Long userId, String sectionKey);

    void deleteByAssetId(Long assetId);
}

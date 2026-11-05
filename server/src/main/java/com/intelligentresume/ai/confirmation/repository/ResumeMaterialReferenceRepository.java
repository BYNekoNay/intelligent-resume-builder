package com.intelligentresume.ai.confirmation.repository;

import com.intelligentresume.ai.confirmation.domain.ResumeMaterialReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 简历版本资料引用仓储。
 *
 * <p>要点:
 * <ul>
 *   <li>引用只按版本查:溯源展示的入口永远是"某个 resume_version 用了哪些资料"。</li>
 *   <li>没有按 materialId 反查的方法 —— 想回答"这条资料被哪些简历用过"需要另加查询。</li>
 *   <li>引用行只追加不更新,所以这里也不需要删除或批量改写的派生方法。</li>
 * </ul>
 */
public interface ResumeMaterialReferenceRepository extends JpaRepository<ResumeMaterialReference, Long> {

    List<ResumeMaterialReference> findByResumeVersionId(Long resumeVersionId);
}

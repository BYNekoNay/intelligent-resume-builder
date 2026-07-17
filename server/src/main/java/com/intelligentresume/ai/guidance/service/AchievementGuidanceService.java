package com.intelligentresume.ai.guidance.service;

import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.ai.guidance.dto.AchievementGuidanceRequest;
import com.intelligentresume.ai.guidance.dto.AchievementGuidanceResponse;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AchievementGuidanceService {
    private final ConsentService consentService;
    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final AiProvider provider;

    public AchievementGuidanceService(ConsentService consentService, ResumeVersionRepository versionRepository,
                                      ResumeRepository resumeRepository, AiProvider provider) {
        this.consentService = consentService;
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.provider = provider;
    }

    public AchievementGuidanceResponse guide(AchievementGuidanceRequest request, Long userId) {
        if (!consentService.isConsented(userId)) throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        ResumeVersion version = versionRepository.findById(request.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Map<String, Object> result = provider.invoke("ACHIEVEMENT_GUIDANCE", Map.of(
                "section", request.section(), "content", request.content(), "resumeContext", version.getResumeJson()));
        Object raw = result.get("questions");
        if (!(raw instanceof List<?> list)) throw new BusinessException(ErrorCode.AI_FAILURE);
        return new AchievementGuidanceResponse(list.stream().map(String::valueOf).toList(), false);
    }
}

package com.intelligentresume.communication.service;

import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.communication.dto.CommunicationGenerateRequest;
import com.intelligentresume.communication.dto.CommunicationGenerateResponse;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class CommunicationService {
    private final ConsentService consentService;
    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final AiProvider provider;

    public CommunicationService(ConsentService consentService, ResumeVersionRepository versionRepository,
            ResumeRepository resumeRepository, JobDescriptionRepository jobRepository, AiProvider provider) {
        this.consentService = consentService;
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.provider = provider;
    }
    public CommunicationGenerateResponse generate(CommunicationGenerateRequest request, Long userId) {
        if (!consentService.isConsented(userId)) throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        ResumeVersion version = versionRepository.findById(request.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        JobDescription job = jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Map<String, Object> result = provider.invoke("COMMUNICATION_GENERATE", Map.of(
                "type", request.type().name(), "resume", version.getResumeJson(), "jobTitle", job.getTitle(), "jobText", job.getJdText()));
        String draft = String.valueOf(result.getOrDefault("draft", "")).trim();
        if (draft.isEmpty()) throw new BusinessException(ErrorCode.AI_FAILURE);
        return new CommunicationGenerateResponse(request.type(), draft, false, true);
    }
}

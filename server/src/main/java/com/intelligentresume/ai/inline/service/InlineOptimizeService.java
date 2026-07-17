package com.intelligentresume.ai.inline.service;

import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.ai.inline.domain.InlineOptimizationRecord;
import com.intelligentresume.ai.inline.dto.InlineOptimizeRequest;
import com.intelligentresume.ai.inline.dto.InlineOptimizeResponse;
import com.intelligentresume.ai.inline.repository.InlineOptimizationRecordRepository;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InlineOptimizeService {

    private final ConsentService consentService;
    private final ResumeVersionRepository versionRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final InlineOptimizationRecordRepository recordRepository;
    private final AiProvider provider;

    public InlineOptimizeService(ConsentService consentService,
                                 ResumeVersionRepository versionRepository,
                                 ResumeRepository resumeRepository,
                                 JobDescriptionRepository jobRepository,
                                 InlineOptimizationRecordRepository recordRepository,
                                 AiProvider provider) {
        this.consentService = consentService;
        this.versionRepository = versionRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.recordRepository = recordRepository;
        this.provider = provider;
    }

    @Transactional
    public InlineOptimizeResponse optimize(InlineOptimizeRequest request, Long userId) {
        if (!consentService.isConsented(userId)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }

        ResumeVersion version = versionRepository.findById(request.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        resumeRepository.findByIdAndUserId(version.getResumeId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        JobDescription job = null;
        if (request.jobDescriptionId() != null) {
            job = jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("section", request.section());
        input.put("content", request.content().trim());
        input.put("resumeContext", version.getResumeJson());
        if (job != null) {
            input.put("jobDescription", job.getJdText());
        }

        Map<String, Object> result;
        try {
            result = provider.invoke("INLINE_OPTIMIZE", input);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_FAILURE);
        }
        List<InlineOptimizeResponse.Candidate> candidates = parseCandidates(result);

        InlineOptimizationRecord record = new InlineOptimizationRecord();
        record.setUserId(userId);
        record.setResumeVersionId(version.getId());
        record.setJobDescriptionId(request.jobDescriptionId());
        record.setSectionCode(request.section());
        record.setOriginalContent(request.content().trim());
        record.setResultJson(result);
        record.setProviderCode(provider.code());
        InlineOptimizationRecord saved = recordRepository.save(record);

        return new InlineOptimizeResponse(saved.getId(), request.section(), request.content().trim(), candidates, true);
    }

    @SuppressWarnings("unchecked")
    private List<InlineOptimizeResponse.Candidate> parseCandidates(Map<String, Object> result) {
        Object rawCandidates = result.get("candidates");
        if (!(rawCandidates instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_FAILURE);
        }
        try {
            return list.stream().map(item -> {
                Map<String, Object> candidate = (Map<String, Object>) item;
                String content = String.valueOf(candidate.getOrDefault("content", "")).trim();
                String suggestion = String.valueOf(candidate.getOrDefault("suggestion", "")).trim();
                if (content.isEmpty() || suggestion.isEmpty()) {
                    throw new BusinessException(ErrorCode.AI_FAILURE);
                }
                return new InlineOptimizeResponse.Candidate(content, suggestion);
            }).toList();
        } catch (ClassCastException exception) {
            throw new BusinessException(ErrorCode.AI_FAILURE);
        }
    }
}

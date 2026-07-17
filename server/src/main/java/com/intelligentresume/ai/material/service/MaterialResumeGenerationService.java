package com.intelligentresume.ai.material.service;

import com.intelligentresume.ai.consent.service.ConsentService;
import com.intelligentresume.ai.material.domain.MaterialResumeGeneration;
import com.intelligentresume.ai.material.dto.MaterialResumeGenerationRequest;
import com.intelligentresume.ai.material.dto.MaterialResumeGenerationResponse;
import com.intelligentresume.ai.material.repository.MaterialResumeGenerationRepository;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
public class MaterialResumeGenerationService {
    private final ConsentService consentService;
    private final JobDescriptionRepository jobRepository;
    private final MaterialResumeGenerationRepository repository;
    private final AiProvider provider;
    public MaterialResumeGenerationService(ConsentService consentService, JobDescriptionRepository jobRepository,
            MaterialResumeGenerationRepository repository, AiProvider provider) {
        this.consentService = consentService; this.jobRepository = jobRepository; this.repository = repository; this.provider = provider;
    }
    @Transactional
    public MaterialResumeGenerationResponse generate(MaterialResumeGenerationRequest request, Long userId) {
        if (!consentService.isConsented(userId)) throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        if (request.jobDescriptionId() != null) jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Map<String, Object> input = Map.of("rawMaterialText", request.rawMaterialText(), "jobDescriptionId", request.jobDescriptionId() == null ? "" : request.jobDescriptionId());
        Map<String, Object> result = provider.invoke("MATERIAL_RESUME_GENERATION", input);
        Object rawResume = result.get("generatedResumeJson"); Object rawSuggestions = result.get("suggestions");
        if (!(rawResume instanceof Map<?, ?>) || !(rawSuggestions instanceof List<?>)) throw new BusinessException(ErrorCode.AI_FAILURE);
        @SuppressWarnings("unchecked") Map<String, Object> resume = (Map<String, Object>) rawResume;
        List<String> suggestions = ((List<?>) rawSuggestions).stream().map(String::valueOf).toList();
        MaterialResumeGeneration entity = new MaterialResumeGeneration(); entity.setUserId(userId); entity.setRawMaterialText(request.rawMaterialText());
        entity.setJobDescriptionId(request.jobDescriptionId()); entity.setGeneratedResumeJson(resume); entity.setSuggestions(suggestions);
        MaterialResumeGeneration saved = repository.save(entity);
        return new MaterialResumeGenerationResponse(saved.getIdValue(), request.rawMaterialText(), resume, suggestions, true);
    }
}

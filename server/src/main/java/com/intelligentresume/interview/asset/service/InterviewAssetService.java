package com.intelligentresume.interview.asset.service;

import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.asset.domain.InterviewAnswerAsset;
import com.intelligentresume.interview.asset.domain.InterviewAssetSection;
import com.intelligentresume.interview.asset.dto.*;
import com.intelligentresume.interview.asset.repository.InterviewAnswerAssetRepository;
import com.intelligentresume.interview.asset.repository.InterviewAssetSectionRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class InterviewAssetService {
    /** 与前端 web/src/resume/sectionRegistry.ts 的 SECTION_KEYS 保持一致。 */
    private static final Set<String> SECTION_KEYS = Set.of(
            "basics", "objective", "links", "work", "volunteering", "skills", "projects",
            "education", "courses", "certificates", "publications", "awards", "languages",
            "customSections");

    private final InterviewAnswerAssetRepository repository;
    private final InterviewAssetSectionRepository sectionRepository;
    private final InterviewRecordRepository recordRepository;
    private final JobDescriptionRepository jobRepository;
    private final CareerMaterialRepository materialRepository;

    public InterviewAssetService(InterviewAnswerAssetRepository repository,
                                 InterviewAssetSectionRepository sectionRepository,
                                 InterviewRecordRepository recordRepository,
                                 JobDescriptionRepository jobRepository,
                                 CareerMaterialRepository materialRepository) {
        this.repository = repository;
        this.sectionRepository = sectionRepository;
        this.recordRepository = recordRepository;
        this.jobRepository = jobRepository;
        this.materialRepository = materialRepository;
    }

    @Transactional(readOnly = true)
    public List<InterviewAssetResponse> list(Long userId, Long jobId, String keyword, String sectionKey,
                                             Long interviewRecordId) {
        if (jobId != null) jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> notFound("岗位不存在"));
        if (sectionKey != null && !SECTION_KEYS.contains(sectionKey)) {
            throw new BusinessException(ErrorCode.VALIDATION, "非法的简历章节: " + sectionKey);
        }
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<InterviewAnswerAsset> assets = repository.search(userId, jobId, interviewRecordId, sectionKey, normalized);
        return attachSections(assets, userId);
    }

    @Transactional
    public InterviewAssetResponse create(InterviewAssetRequest request, Long userId) {
        validateRecord(request.interviewRecordId(), userId);
        validateSectionKeys(request.sectionKeys());
        validateMaterials(request.materialIds(), userId);

        // 幂等：(userId, interviewRecordId) 已存在资产时返回已有资产（不重复创建）
        if (request.interviewRecordId() != null) {
            InterviewAnswerAsset existing = repository
                    .findByUserIdAndInterviewRecordId(userId, request.interviewRecordId()).orElse(null);
            if (existing != null) {
                return response(existing, userId);
            }
        }

        InterviewAnswerAsset asset = new InterviewAnswerAsset();
        asset.setUserId(userId);
        asset.setInterviewRecordId(request.interviewRecordId());
        applyContent(asset, request);
        asset = repository.saveAndFlush(asset);
        replaceSections(asset.getId(), userId, request.sectionKeys(), request.materialIds());
        return response(asset, userId);
    }

    @Transactional
    public InterviewAssetResponse update(Long id, InterviewAssetRequest request, Long userId) {
        InterviewAnswerAsset asset = owned(id, userId);
        if (request.interviewRecordId() != null) {
            validateRecord(request.interviewRecordId(), userId);
            asset.setInterviewRecordId(request.interviewRecordId());
        }
        applyContent(asset, request);
        validateSectionKeys(request.sectionKeys());
        validateMaterials(request.materialIds(), userId);
        asset = repository.saveAndFlush(asset);
        replaceSections(asset.getId(), userId, request.sectionKeys(), request.materialIds());
        return response(asset, userId);
    }

    @Transactional
    public void delete(Long id, Long userId) {
        InterviewAnswerAsset asset = owned(id, userId);
        sectionRepository.deleteByAssetId(asset.getId());
        repository.delete(asset);
    }

    private void validateRecord(Long recordId, Long userId) {
        if (recordId != null) recordRepository.findOwned(recordId, userId)
                .orElseThrow(() -> notFound("面试回答记录不存在"));
    }

    private void validateSectionKeys(List<String> sectionKeys) {
        if (sectionKeys == null) return;
        for (String key : sectionKeys) {
            if (!SECTION_KEYS.contains(key)) {
                throw new BusinessException(ErrorCode.VALIDATION, "非法的简历章节: " + key);
            }
        }
    }

    private void validateMaterials(List<Long> materialIds, Long userId) {
        if (materialIds == null) return;
        for (Long materialId : materialIds) {
            if (materialId != null && materialRepository.findByIdAndUserId(materialId, userId).isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "职业素材不存在");
            }
        }
    }

    private void replaceSections(Long assetId, Long userId, List<String> sectionKeys, List<Long> materialIds) {
        sectionRepository.deleteByAssetId(assetId);
        List<String> keys = sectionKeys == null ? List.of() : sectionKeys.stream().distinct().toList();
        List<Long> materials = materialIds == null ? List.of() : materialIds.stream().distinct().toList();
        if (keys.isEmpty() && materials.isEmpty()) return;
        if (keys.isEmpty()) {
            // 未选章节时素材挂在空章节下：保证 section_key NOT NULL 且不丢失素材关联
            for (Long materialId : materials) {
                saveSection(assetId, userId, "", materialId);
            }
            return;
        }
        for (String key : keys) {
            if (materials.isEmpty()) {
                saveSection(assetId, userId, key, null);
            } else {
                for (Long materialId : materials) {
                    saveSection(assetId, userId, key, materialId);
                }
            }
        }
    }

    private void saveSection(Long assetId, Long userId, String sectionKey, Long materialId) {
        InterviewAssetSection section = new InterviewAssetSection();
        section.setUserId(userId);
        section.setAssetId(assetId);
        section.setSectionKey(sectionKey);
        section.setMaterialId(materialId);
        sectionRepository.save(section);
    }

    private void applyContent(InterviewAnswerAsset asset, InterviewAssetRequest request) {
        asset.setQuestionText(request.questionText().trim());
        asset.setOriginalAnswerText(request.originalAnswerText().trim());
        asset.setSuggestedAnswerText(blankToNull(request.suggestedAnswerText()));
        asset.setFeedbackJson(request.feedbackJson());
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private InterviewAnswerAsset owned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(() -> notFound("面试答案资产不存在"));
    }
    private BusinessException notFound(String message) { return new BusinessException(ErrorCode.NOT_FOUND, message); }

    private List<InterviewAssetResponse> attachSections(List<InterviewAnswerAsset> assets, Long userId) {
        if (assets.isEmpty()) return List.of();
        Map<Long, List<InterviewAssetSection>> byAsset = sectionRepository
                .findByAssetIdIn(assets.stream().map(InterviewAnswerAsset::getId).toList())
                .stream().collect(Collectors.groupingBy(InterviewAssetSection::getAssetId));
        return assets.stream().map(asset -> response(asset, userId, byAsset.getOrDefault(asset.getId(), List.of()))).toList();
    }

    private InterviewAssetResponse response(InterviewAnswerAsset asset, Long userId) {
        return response(asset, userId, sectionRepository.findByAssetId(asset.getId()));
    }

    private InterviewAssetResponse response(InterviewAnswerAsset asset, Long userId,
                                            List<InterviewAssetSection> sections) {
        LinkedHashSet<String> sectionKeys = new LinkedHashSet<>();
        LinkedHashSet<Long> materialIds = new LinkedHashSet<>();
        for (InterviewAssetSection section : sections) {
            if (section.getSectionKey() != null) sectionKeys.add(section.getSectionKey());
            if (section.getMaterialId() != null) materialIds.add(section.getMaterialId());
        }
        return new InterviewAssetResponse(asset.getId(), asset.getInterviewRecordId(), asset.getQuestionText(),
                asset.getOriginalAnswerText(), asset.getSuggestedAnswerText(), asset.getFeedbackJson(),
                asset.getCreatedAt(), asset.getUpdatedAt(),
                new ArrayList<>(sectionKeys), new ArrayList<>(materialIds));
    }
}

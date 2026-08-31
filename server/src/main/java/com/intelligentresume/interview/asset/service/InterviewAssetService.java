package com.intelligentresume.interview.asset.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.asset.domain.InterviewAnswerAsset;
import com.intelligentresume.interview.asset.dto.*;
import com.intelligentresume.interview.asset.repository.InterviewAnswerAssetRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class InterviewAssetService {
    private final InterviewAnswerAssetRepository repository;
    private final InterviewRecordRepository recordRepository;
    private final JobDescriptionRepository jobRepository;

    public InterviewAssetService(InterviewAnswerAssetRepository repository, InterviewRecordRepository recordRepository,
                                 JobDescriptionRepository jobRepository) {
        this.repository = repository;
        this.recordRepository = recordRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional(readOnly = true)
    public List<InterviewAssetResponse> list(Long userId, Long jobId, String keyword) {
        if (jobId != null) jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> notFound("岗位不存在"));
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return repository.search(userId, jobId, normalized).stream().map(this::response).toList();
    }

    @Transactional
    public InterviewAssetResponse create(InterviewAssetRequest request, Long userId) {
        validateRecord(request.interviewRecordId(), userId);
        InterviewAnswerAsset asset = new InterviewAnswerAsset();
        asset.setUserId(userId);
        asset.setInterviewRecordId(request.interviewRecordId());
        applyContent(asset, request);
        return response(repository.saveAndFlush(asset));
    }

    @Transactional
    public InterviewAssetResponse update(Long id, InterviewAssetRequest request, Long userId) {
        InterviewAnswerAsset asset = owned(id, userId);
        if (request.interviewRecordId() != null) {
            validateRecord(request.interviewRecordId(), userId);
            asset.setInterviewRecordId(request.interviewRecordId());
        }
        applyContent(asset, request);
        return response(repository.saveAndFlush(asset));
    }

    @Transactional
    public void delete(Long id, Long userId) { repository.delete(owned(id, userId)); }

    private void validateRecord(Long recordId, Long userId) {
        if (recordId != null) recordRepository.findOwned(recordId, userId)
                .orElseThrow(() -> notFound("面试回答记录不存在"));
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
    private InterviewAssetResponse response(InterviewAnswerAsset asset) {
        return new InterviewAssetResponse(asset.getId(), asset.getInterviewRecordId(), asset.getQuestionText(),
                asset.getOriginalAnswerText(), asset.getSuggestedAnswerText(), asset.getFeedbackJson(),
                asset.getCreatedAt(), asset.getUpdatedAt(), List.of(), List.of());
    }
}

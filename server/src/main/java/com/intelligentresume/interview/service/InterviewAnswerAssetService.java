package com.intelligentresume.interview.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.interview.domain.InterviewAnswerAsset;
import com.intelligentresume.interview.dto.InterviewAnswerAssetCreateRequest;
import com.intelligentresume.interview.dto.InterviewAnswerAssetResponse;
import com.intelligentresume.interview.repository.InterviewAnswerAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class InterviewAnswerAssetService {
    private final InterviewAnswerAssetRepository repository;
    public InterviewAnswerAssetService(InterviewAnswerAssetRepository repository) { this.repository = repository; }
    @Transactional
    public InterviewAnswerAssetResponse create(InterviewAnswerAssetCreateRequest request, Long userId) {
        InterviewAnswerAsset asset = new InterviewAnswerAsset();
        asset.setUserId(userId); asset.setInterviewRecordId(request.interviewRecordId());
        asset.setQuestionText(request.questionText()); asset.setOriginalAnswerText(request.originalAnswerText());
        asset.setSuggestedAnswerText(request.suggestedAnswerText()); asset.setFeedbackJson(request.feedbackJson());
        return toResponse(repository.save(asset));
    }
    public List<InterviewAnswerAssetResponse> list(Long userId, Long jobDescriptionId, String keyword) {
        List<InterviewAnswerAsset> assets = jobDescriptionId == null
                ? repository.findByUserIdOrderByCreatedAtDesc(userId)
                : repository.findByUserIdAndJobDescriptionIdOrderByCreatedAtDesc(userId, jobDescriptionId);
        if (keyword == null || keyword.isBlank()) return assets.stream().map(this::toResponse).toList();
        String normalized = keyword.trim().toLowerCase();
        return assets.stream().filter(asset -> searchableText(asset).contains(normalized))
                .map(this::toResponse).toList();
    }

    private String searchableText(InterviewAnswerAsset asset) {
        return (asset.getQuestionText() + " " + asset.getSuggestedAnswerText() + " "
                + String.valueOf(asset.getFeedbackJson())).toLowerCase();
    }
    private InterviewAnswerAssetResponse toResponse(InterviewAnswerAsset asset) {
        return new InterviewAnswerAssetResponse(asset.getIdValue(), asset.getInterviewRecordId(), asset.getQuestionText(),
                asset.getOriginalAnswerText(), asset.getSuggestedAnswerText(), asset.getFeedbackJson(), asset.getCreatedAt(), asset.getUpdatedAt());
    }
}

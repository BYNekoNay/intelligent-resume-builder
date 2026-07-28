package com.intelligentresume.communication.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.dto.CommunicationResponse;
import com.intelligentresume.communication.dto.GenerateCommunicationRequest;
import com.intelligentresume.communication.repository.CommunicationDraftRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class CommunicationService {
    private final ResumeVersionRepository versionRepository;
    private final JobDescriptionRepository jobRepository;
    private final CommunicationDraftRepository draftRepository;

    public CommunicationService(ResumeVersionRepository versionRepository, JobDescriptionRepository jobRepository,
                                CommunicationDraftRepository draftRepository) {
        this.versionRepository = versionRepository;
        this.jobRepository = jobRepository;
        this.draftRepository = draftRepository;
    }

    @Transactional
    public CommunicationResponse generate(GenerateCommunicationRequest request, Long userId) {
        ResumeVersion version = versionRepository.findById(request.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        if (!userId.equals(version.getCreatedBy()) || version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
        JobDescription job = jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "JD 不存在"));
        String draftText = buildDraft(request, version.getResumeJson(), job);
        CommunicationDraft entity = new CommunicationDraft();
        entity.setUserId(userId);
        entity.setResumeVersionId(request.resumeVersionId());
        entity.setJobDescriptionId(request.jobDescriptionId());
        entity.setType(request.type());
        entity.setDraftText(draftText);
        draftRepository.save(entity);
        return new CommunicationResponse(request.type(), draftText, false, true);
    }

    private String buildDraft(GenerateCommunicationRequest request, Map<String, Object> resume, JobDescription job) {
        String name = "候选人";
        Object basicsValue = resume == null ? null : resume.get("basics");
        if (basicsValue instanceof Map<?, ?> basics && basics.get("name") instanceof String value && !value.isBlank()) name = value;
        String skill = firstSkill(resume == null ? null : resume.get("skills"));
        String company = job.getCompanyName() == null || job.getCompanyName().isBlank() ? "贵公司" : job.getCompanyName();
        String evidence = skill.isBlank() ? "请以随附简历中的事实材料为准" : "我的简历记录了 " + skill + " 相关经验";
        return switch (request.type()) {
            case COVER_LETTER -> "您好，我是" + name + "。我希望申请" + company + "的" + job.getTitle() + "岗位。" + evidence + "。请审阅我的简历，期待进一步沟通。";
            case EMAIL -> "主题：申请" + job.getTitle() + "岗位\n\n您好，我是" + name + "。随信附上岗位申请材料，" + evidence + "。感谢审阅。";
            case OPENING_MESSAGE -> "您好，我是" + name + "，关注到" + company + "的" + job.getTitle() + "岗位。" + evidence + "，希望有机会进一步交流。";
        };
    }

    private String firstSkill(Object value) {
        if (!(value instanceof List<?> skills) || skills.isEmpty()) return "";
        Object first = skills.get(0);
        if (first instanceof String text) return text;
        if (first instanceof Map<?, ?> item) return String.valueOf(item.get("name") == null ? "" : item.get("name"));
        return "";
    }
}

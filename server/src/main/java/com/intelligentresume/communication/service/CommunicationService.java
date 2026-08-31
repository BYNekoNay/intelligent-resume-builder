package com.intelligentresume.communication.service;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.domain.CommunicationGenerationSource;
import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
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
    private final CommunicationAiPromptBuilder promptBuilder;
    private final AiTaskService aiTaskService;

    public CommunicationService(ResumeVersionRepository versionRepository, JobDescriptionRepository jobRepository,
                                CommunicationDraftRepository draftRepository,
                                CommunicationAiPromptBuilder promptBuilder,
                                AiTaskService aiTaskService) {
        this.versionRepository = versionRepository;
        this.jobRepository = jobRepository;
        this.draftRepository = draftRepository;
        this.promptBuilder = promptBuilder;
        this.aiTaskService = aiTaskService;
    }

    public AiTaskStatusResponse generateWithAi(GenerateCommunicationRequest request, String idempotencyKey,
                                               Long userId) {
        ResumeVersion version = ownedResumeVersion(request.resumeVersionId(), userId);
        JobDescription job = ownedJob(request.jobDescriptionId(), userId);
        Map<String, Object> input = promptBuilder.buildTaskInput(request, version.getResumeJson(), job);
        CreateAiTaskRequest taskRequest = new CreateAiTaskRequest(
                AiTaskType.COMMUNICATION_GENERATE, input, null, request.jobDescriptionId(),
                null, null, null, null);
        return aiTaskService.create(taskRequest, idempotencyKey, userId);
    }

    private ResumeVersion ownedResumeVersion(Long id, Long userId) {
        return versionRepository.findByIdAndCreatedByAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
    }

    private JobDescription ownedJob(Long id, Long userId) {
        return jobRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));
    }

    @Transactional
    public CommunicationResponse generate(GenerateCommunicationRequest request, Long userId) {
        ResumeVersion version = versionRepository.findById(request.resumeVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        if (!userId.equals(version.getCreatedBy()) || version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
        JobDescription job = jobRepository.findByIdAndUserId(request.jobDescriptionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));
        String draftText = buildDraft(request, version.getResumeJson(), job);
        CommunicationDraft entity = new CommunicationDraft();
        entity.setUserId(userId);
        entity.setResumeVersionId(request.resumeVersionId());
        entity.setJobDescriptionId(request.jobDescriptionId());
        entity.setType(request.type());
        entity.setDraftText(draftText);
        draftRepository.save(entity);
        return new CommunicationResponse(request.type(), draftText, false, true,
                CommunicationGenerationSource.TEMPLATE);
    }

    private String buildDraft(GenerateCommunicationRequest request, Map<String, Object> resume, JobDescription job) {
        CommunicationOutputLanguage language = request.normalizedLanguage();
        String name = language == CommunicationOutputLanguage.EN ? "the candidate" : "候选人";
        Object basicsValue = resume == null ? null : resume.get("basics");
        if (basicsValue instanceof Map<?, ?> basics && basics.get("name") instanceof String value && !value.isBlank()) {
            name = value;
        }
        String skill = firstSkill(resume == null ? null : resume.get("skills"));
        String company = job.getCompanyName() == null || job.getCompanyName().isBlank()
                ? (language == CommunicationOutputLanguage.EN ? "your company" : "贵公司")
                : job.getCompanyName();
        if (language == CommunicationOutputLanguage.EN) {
            return buildEnglishDraft(request, name, skill, company, job.getTitle());
        }
        String evidence = skill.isBlank() ? "相关经历详见随附简历" : "我的简历记录了 " + skill + " 相关经验";
        return switch (request.type()) {
            case COVER_LETTER -> "您好，我是" + name + "。我希望申请" + company + "的" + job.getTitle()
                    + "岗位。" + evidence + "。请审阅我的简历，期待进一步沟通。";
            case EMAIL -> "主题：申请" + job.getTitle() + "岗位\n\n您好，我是" + name
                    + "。随信附上岗位申请材料，" + evidence + "。感谢审阅。";
            case OPENING_MESSAGE -> "您好，我是" + name + "，关注到" + company + "的" + job.getTitle()
                    + "岗位。" + evidence + "，希望有机会进一步交流。";
        };
    }

    private String buildEnglishDraft(GenerateCommunicationRequest request, String name, String skill,
                                     String company, String jobTitle) {
        String evidence = skill.isBlank()
                ? "My relevant experience is detailed in the attached resume"
                : "My resume includes relevant experience with " + skill;
        return switch (request.type()) {
            case COVER_LETTER -> "Hello, I am " + name + ". I would like to apply for the " + jobTitle
                    + " role at " + company + ". " + evidence
                    + ". Please review my resume; I look forward to discussing the opportunity.";
            case EMAIL -> "Subject: Application for the " + jobTitle + " role\n\nHello, I am " + name
                    + ". I have attached my application materials. " + evidence + ". Thank you for your time.";
            case OPENING_MESSAGE -> "Hello, I am " + name + ". I noticed the " + jobTitle + " role at "
                    + company + ". " + evidence + ", and I would welcome the chance to discuss it further.";
        };
    }

    private String firstSkill(Object value) {
        if (!(value instanceof List<?> skills) || skills.isEmpty()) return "";
        Object first = skills.get(0);
        if (first instanceof String text) return text;
        if (first instanceof Map<?, ?> item) {
            return String.valueOf(item.get("name") == null ? "" : item.get("name"));
        }
        return "";
    }
}

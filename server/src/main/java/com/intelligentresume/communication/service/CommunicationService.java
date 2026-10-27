package com.intelligentresume.communication.service;

import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.domain.CommunicationGenerationSource;
import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationTemplate;
import com.intelligentresume.communication.dto.CommunicationResponse;
import com.intelligentresume.communication.dto.GenerateCommunicationRequest;
import com.intelligentresume.communication.dto.SaveDraftRequest;
import com.intelligentresume.communication.repository.CommunicationDraftRepository;
import com.intelligentresume.communication.repository.CommunicationTemplateRepository;
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
    private final CommunicationTemplateRepository templateRepository;
    private final AiPreviewService previewService;
    private final AiPreviewGuard previewGuard;

    public CommunicationService(ResumeVersionRepository versionRepository, JobDescriptionRepository jobRepository,
                                CommunicationDraftRepository draftRepository,
                                CommunicationAiPromptBuilder promptBuilder,
                                AiTaskService aiTaskService,
                                CommunicationTemplateRepository templateRepository,
                                AiPreviewService previewService,
                                AiPreviewGuard previewGuard) {
        this.versionRepository = versionRepository;
        this.jobRepository = jobRepository;
        this.draftRepository = draftRepository;
        this.promptBuilder = promptBuilder;
        this.aiTaskService = aiTaskService;
        this.templateRepository = templateRepository;
        this.previewService = previewService;
        this.previewGuard = previewGuard;
    }

    /**
     * 组装「要落库的任务 input」与「worker 实际交给模型的三段式 prompt」—— 创建与预览**共用同一实现**
     * （R32 AC-R32-8）。
     *
     * <p>为什么必须连 prompt 一起算：{@code CommunicationAiService.executeTask} 是拿任务 input 现场调
     * {@link CommunicationAiPromptBuilder#build(Map)} 得到消息的，**不走** {@code PromptTemplates} 回落。
     * 预览若只重放 input 就交出去，看到的正文会是另一套模板，与实际发送的不一致。
     */
    public record PreparedInput(Map<String, Object> taskInput, Map<String, Object> providerInput) {
    }

    /**
     * @throws BusinessException 版本/岗位不属于当前用户 → {@code 40401}；版本已归档 → {@code 40902}
     */
    public PreparedInput prepareInput(GenerateCommunicationRequest request, Long userId) {
        ResumeVersion version = ownedResumeVersion(request.resumeVersionId(), userId);
        JobDescription job = ownedJob(request.jobDescriptionId(), userId);
        Map<String, Object> taskInput = promptBuilder.buildTaskInput(request, version.getResumeJson(), job);
        return new PreparedInput(taskInput, promptBuilder.build(taskInput));
    }

    public AiTaskStatusResponse generateWithAi(GenerateCommunicationRequest request, String idempotencyKey,
                                               String previewToken, Long userId) {
        PreparedInput prepared = prepareInput(request, userId);
        CreateAiTaskRequest taskRequest = new CreateAiTaskRequest(
                AiTaskType.COMMUNICATION_GENERATE, prepared.taskInput(), null, request.jobDescriptionId(),
                null, null, null, null);
        // R32 AC-R32-9 / AC-R32-10：令牌用服务端自己组装出的 providerInput 重算并比对。
        // 位置由 AiTaskService 保证：幂等重放之后（重试不因缺令牌失败）、配额与写入之前（被拒不落库）。
        return aiTaskService.create(taskRequest, idempotencyKey, userId,
                () -> previewGuard.require(AiTaskType.COMMUNICATION_GENERATE, prepared.providerInput(), previewToken));
    }

    /** 发送前预览（AC-R32-8 / AC-R32-12）：只读，不落库、不调 provider、不扣配额、不校验幂等键。 */
    public AiPreviewService.PreviewResponse preview(GenerateCommunicationRequest request, Long userId) {
        PreparedInput prepared = prepareInput(request, userId);
        return previewService.preview(new AiPreviewService.PreviewRequest(
                AiTaskType.COMMUNICATION_GENERATE, prepared.providerInput(), false));
    }

    private ResumeVersion ownedResumeVersion(Long id, Long userId) {
        ResumeVersion version = versionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在"));
        // 归属校验先于归档判定：不通过时一律 404，避免用归档状态反推他人版本是否存在
        if (!userId.equals(version.getCreatedBy())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历版本不存在");
        }
        // 归档是可逆状态：与 ATS/评分/导出/投递/面试一致用 409 + 可操作文案
        if (version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.VERSION_ARCHIVED, "该简历版本已归档，请先恢复后再发起沟通");
        }
        return version;
    }

    private JobDescription ownedJob(Long id, Long userId) {
        return jobRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位描述不存在"));
    }

    /**
     * 确认保存草稿（绝不发送）。templateId 存在时校验模板可见性并 usage_count+1。
     */
    @Transactional
    public CommunicationResponse saveDraft(SaveDraftRequest request, Long userId) {
        ResumeVersion version = ownedResumeVersion(request.resumeVersionId(), userId);
        JobDescription job = ownedJob(request.jobDescriptionId(), userId);
        if (request.templateId() != null) {
            CommunicationTemplate template = templateRepository.findById(request.templateId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "沟通模板不存在"));
            if (template.getUserId() != null && !userId.equals(template.getUserId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "沟通模板不存在");
            }
            // #25：计数改为原子自增，避免读改写丢增量（计数不推进乐观锁版本）。
            templateRepository.incrementUsageCount(template.getId());
        }
        CommunicationDraft entity = new CommunicationDraft();
        entity.setUserId(userId);
        entity.setResumeVersionId(request.resumeVersionId());
        entity.setJobDescriptionId(request.jobDescriptionId());
        entity.setType(request.type());
        entity.setDraftText(request.draftText());
        draftRepository.save(entity);
        return new CommunicationResponse(request.type(), request.draftText(), false, true,
                CommunicationGenerationSource.TEMPLATE);
    }

    @Transactional
    public CommunicationResponse generate(GenerateCommunicationRequest request, Long userId) {
        ResumeVersion version = ownedResumeVersion(request.resumeVersionId(), userId);
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

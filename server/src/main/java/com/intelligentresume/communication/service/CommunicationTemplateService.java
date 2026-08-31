package com.intelligentresume.communication.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationTemplate;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;
import com.intelligentresume.communication.dto.SaveTemplateRequest;
import com.intelligentresume.communication.dto.TemplatePreviewResponse;
import com.intelligentresume.communication.dto.TemplateSummaryResponse;
import com.intelligentresume.communication.dto.UpdateTemplateRequest;
import com.intelligentresume.communication.repository.CommunicationTemplateRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 沟通模板服务：模板库列表、真实资料预览、自定义模板 CRUD（归属校验）。
 */
@Service
public class CommunicationTemplateService {

    private final CommunicationTemplateRepository templateRepository;
    private final ResumeVersionRepository versionRepository;
    private final JobDescriptionRepository jobRepository;
    private final TemplatePlaceholderService placeholderService;

    public CommunicationTemplateService(CommunicationTemplateRepository templateRepository,
                                        ResumeVersionRepository versionRepository,
                                        JobDescriptionRepository jobRepository,
                                        TemplatePlaceholderService placeholderService) {
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
        this.jobRepository = jobRepository;
        this.placeholderService = placeholderService;
    }

    @Transactional(readOnly = true)
    public List<TemplateSummaryResponse> list(Long userId, TemplateScene scene, CommunicationType type,
                                              CommunicationOutputLanguage language) {
        return templateRepository.search(userId, scene, type, language).stream()
                .map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public TemplatePreviewResponse preview(Long id, Long resumeVersionId, Long jobDescriptionId, Long userId) {
        CommunicationTemplate template = visible(id, userId);
        ResumeVersion version = ownedResumeVersion(resumeVersionId, userId);
        JobDescription job = ownedJob(jobDescriptionId, userId);
        TemplatePlaceholderService.FillResult fill =
                placeholderService.fill(template.getBodyText(), version.getResumeJson(), job);
        return new TemplatePreviewResponse(template.getId(), template.getName(), template.getScene(),
                template.getTemplateType(), fill.filledBody(), fill.missingPlaceholders());
    }

    @Transactional
    public TemplateSummaryResponse saveCustom(SaveTemplateRequest request, Long userId) {
        placeholderService.validate(request.bodyText());
        CommunicationTemplate template = new CommunicationTemplate();
        template.setUserId(userId);
        template.setScene(request.scene());
        template.setTemplateType(request.type());
        template.setOutputLanguage(request.outputLanguage() != null ? request.outputLanguage() : CommunicationOutputLanguage.ZH_CN);
        template.setName(request.name().trim());
        template.setDescription(blankToNull(request.description()));
        template.setBodyText(request.bodyText());
        template.setSystem(false);
        template.setUsageCount(0);
        return summary(templateRepository.saveAndFlush(template));
    }

    @Transactional
    public TemplateSummaryResponse update(Long id, UpdateTemplateRequest request, Long userId) {
        CommunicationTemplate template = ownedCustom(id, userId);
        placeholderService.validate(request.bodyText());
        template.setScene(request.scene());
        template.setTemplateType(request.type());
        template.setOutputLanguage(request.outputLanguage() != null ? request.outputLanguage() : CommunicationOutputLanguage.ZH_CN);
        template.setName(request.name().trim());
        template.setDescription(blankToNull(request.description()));
        template.setBodyText(request.bodyText());
        return summary(templateRepository.saveAndFlush(template));
    }

    @Transactional
    public void delete(Long id, Long userId) {
        templateRepository.delete(ownedCustom(id, userId));
    }

    /**
     * 模板可见性：内置模板（userId=null）所有人可见；自定义模板仅本人可见。
     */
    public CommunicationTemplate visible(Long id, Long userId) {
        CommunicationTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> notFound("沟通模板不存在"));
        if (template.getUserId() != null && !userId.equals(template.getUserId())) {
            throw notFound("沟通模板不存在");
        }
        return template;
    }

    /**
     * 自定义模板归属校验：内置模板修改/删除 → 40301；他人模板 → 40401。
     */
    public CommunicationTemplate ownedCustom(Long id, Long userId) {
        CommunicationTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> notFound("沟通模板不存在"));
        if (template.isSystem() || template.getUserId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内置模板只读，不允许修改或删除");
        }
        if (!userId.equals(template.getUserId())) {
            throw notFound("沟通模板不存在");
        }
        return template;
    }

    private ResumeVersion ownedResumeVersion(Long id, Long userId) {
        return versionRepository.findByIdAndCreatedByAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> notFound("简历版本不存在"));
    }

    private JobDescription ownedJob(Long id, Long userId) {
        return jobRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> notFound("岗位描述不存在"));
    }

    private TemplateSummaryResponse summary(CommunicationTemplate template) {
        return new TemplateSummaryResponse(template.getId(), template.getScene(), template.getTemplateType(),
                template.getOutputLanguage(), template.getName(), template.getDescription(),
                template.isSystem(), template.getUsageCount());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }
}

package com.intelligentresume.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.communication.repository.CommunicationDraftRepository;
import com.intelligentresume.communication.repository.CommunicationTemplateRepository;
import com.intelligentresume.interview.asset.domain.InterviewAnswerAsset;
import com.intelligentresume.interview.asset.domain.InterviewAssetSection;
import com.intelligentresume.interview.asset.repository.InterviewAnswerAssetRepository;
import com.intelligentresume.interview.asset.repository.InterviewAssetSectionRepository;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 账号数据导出（ideation #7）：把当前用户在各业务域的个人数据聚合为一个可下载的 JSON 文档。
 *
 * <p>口径（与产品确认一致）：包含简历与版本、职业资料、目标岗位（JD）、投递记录、
 * 面试会话与答案资产、沟通模板与草稿、个人资料、AI 同意记录。**不含** AI 任务及其
 * 内联快照/派生分析结果（ATS 体检、匹配评分等可再生成的中间产物），也不含登录会话
 * 凭据字段——{@code User} 经白名单取值，不序列化 {@code passwordHash}。
 *
 * <p>实体之间没有 JPA 关联，因此直接序列化实体不存在懒加载问题；列表查询全部以
 * {@code userId} 为条件，导出范围严格限定为本人。结构版本见 {@link #FORMAT_VERSION}。
 */
@Service
public class AccountExportService {

    /** 导出文档结构版本：字段增删时递增，便于消费者识别格式。 */
    private static final int FORMAT_VERSION = 1;

    private final UserRepository userRepository;
    private final PersonalProfileRepository personalProfileRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final CareerMaterialRepository careerMaterialRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final ApplicationRecordRepository applicationRecordRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewRecordRepository interviewRecordRepository;
    private final InterviewAnswerAssetRepository interviewAnswerAssetRepository;
    private final InterviewAssetSectionRepository interviewAssetSectionRepository;
    private final CommunicationTemplateRepository communicationTemplateRepository;
    private final CommunicationDraftRepository communicationDraftRepository;
    private final AiConsentRepository aiConsentRepository;
    private final ObjectMapper objectMapper;

    public AccountExportService(UserRepository userRepository,
                                PersonalProfileRepository personalProfileRepository,
                                ResumeRepository resumeRepository,
                                ResumeVersionRepository resumeVersionRepository,
                                CareerMaterialRepository careerMaterialRepository,
                                JobDescriptionRepository jobDescriptionRepository,
                                ApplicationRecordRepository applicationRecordRepository,
                                InterviewSessionRepository interviewSessionRepository,
                                InterviewRecordRepository interviewRecordRepository,
                                InterviewAnswerAssetRepository interviewAnswerAssetRepository,
                                InterviewAssetSectionRepository interviewAssetSectionRepository,
                                CommunicationTemplateRepository communicationTemplateRepository,
                                CommunicationDraftRepository communicationDraftRepository,
                                AiConsentRepository aiConsentRepository,
                                ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.personalProfileRepository = personalProfileRepository;
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.careerMaterialRepository = careerMaterialRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.applicationRecordRepository = applicationRecordRepository;
        this.interviewSessionRepository = interviewSessionRepository;
        this.interviewRecordRepository = interviewRecordRepository;
        this.interviewAnswerAssetRepository = interviewAnswerAssetRepository;
        this.interviewAssetSectionRepository = interviewAssetSectionRepository;
        this.communicationTemplateRepository = communicationTemplateRepository;
        this.communicationDraftRepository = communicationDraftRepository;
        this.aiConsentRepository = aiConsentRepository;
        this.objectMapper = objectMapper;
    }

    /** 返回缩进格式的导出 JSON（供下载；不记日志，避免用户数据进入日志）。 */
    @Transactional(readOnly = true)
    public String exportAsJson(Long userId) {
        Map<String, Object> payload = buildPayload(userId);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL, "导出数据序列化失败");
        }
    }

    private Map<String, Object> buildPayload(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formatVersion", FORMAT_VERSION);
        payload.put("exportedAt", LocalDateTime.now());
        payload.put("account", accountInfo(user));
        payload.put("personalProfile", personalProfileRepository.findByUserId(userId).orElse(null));
        payload.put("resumes", resumeNodes(userId));
        payload.put("careerMaterials", careerMaterialRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        payload.put("jobDescriptions", jobDescriptionRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        payload.put("applications", applicationRecordRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        payload.put("interviewSessions", interviewSessionNodes(userId));
        payload.put("interviewAnswerAssets", interviewAnswerAssetNodes(userId));
        payload.put("communicationTemplates", communicationTemplateRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        payload.put("communicationDrafts", communicationDraftRepository.findByUserIdOrderByCreatedAtDesc(userId));
        payload.put("aiConsents", aiConsentRepository.findByUserIdOrderByCreatedAtDesc(userId));
        return payload;
    }

    /** 账户标识信息白名单：不导出 {@code passwordHash} 等凭据字段。 */
    private Map<String, Object> accountInfo(User user) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", user.getId());
        info.put("username", user.getUsername());
        info.put("email", user.getEmail());
        info.put("displayName", user.getDisplayName());
        info.put("registeredAt", user.getCreatedAt());
        return info;
    }

    private List<ObjectNode> resumeNodes(Long userId) {
        List<Resume> resumes = resumeRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (resumes.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ResumeVersion>> versionsByResumeId = resumeVersionRepository
                .findByResumeIdInOrderByResumeIdAscVersionNoAsc(resumes.stream().map(Resume::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ResumeVersion::getResumeId));
        return resumes.stream()
                .map(resume -> attachChildren(objectMapper.valueToTree(resume), "versions",
                        versionsByResumeId.getOrDefault(resume.getId(), List.of())))
                .toList();
    }

    private List<ObjectNode> interviewSessionNodes(Long userId) {
        List<InterviewSession> sessions = interviewSessionRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId);
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<Long, List<InterviewRecord>> recordsBySessionId = interviewRecordRepository
                .findBySessionIdInOrderBySessionIdAscRoundNoAscIdAsc(sessions.stream().map(InterviewSession::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(InterviewRecord::getSessionId));
        return sessions.stream()
                .map(session -> attachChildren(objectMapper.valueToTree(session), "records",
                        recordsBySessionId.getOrDefault(session.getId(), List.of())))
                .toList();
    }

    private List<ObjectNode> interviewAnswerAssetNodes(Long userId) {
        List<InterviewAnswerAsset> assets = interviewAnswerAssetRepository.search(userId, null, null, null, null);
        if (assets.isEmpty()) {
            return List.of();
        }
        Map<Long, List<InterviewAssetSection>> sectionsByAssetId = interviewAssetSectionRepository
                .findByAssetIdIn(assets.stream().map(InterviewAnswerAsset::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(InterviewAssetSection::getAssetId));
        return assets.stream()
                .map(asset -> attachChildren(objectMapper.valueToTree(asset), "sections",
                        sectionsByAssetId.getOrDefault(asset.getId(), List.of())))
                .toList();
    }

    /** 把子资源数组挂到父节点上（实体间无 JPA 关联，嵌套关系在导出时显式组装）。 */
    private ObjectNode attachChildren(ObjectNode parent, String field, List<?> children) {
        parent.set(field, objectMapper.valueToTree(children));
        return parent;
    }
}
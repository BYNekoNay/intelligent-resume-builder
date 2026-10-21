package com.intelligentresume.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.domain.ApplicationStatusHistory;
import com.intelligentresume.application.repository.ApplicationStatusHistoryRepository;
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

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final int FORMAT_VERSION = 3;

    /** 导出来源标识：稳定记录 ID 的命名空间前缀，桌面端据此避免与本地记录撞号。 */
    private static final String EXPORT_SOURCE = "web";

    /** 参与稳定 ID 的集合（顺序即 manifest 中的顺序）。 */
    private static final List<String> EXPORT_COLLECTIONS = List.of(
            "resumes", "careerMaterials", "jobDescriptions", "applications",
            "interviewSessions", "interviewAnswerAssets",
            "communicationTemplates", "communicationDrafts", "aiConsents");

    private final UserRepository userRepository;
    private final PersonalProfileRepository personalProfileRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final CareerMaterialRepository careerMaterialRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final ApplicationRecordRepository applicationRecordRepository;
    private final ApplicationStatusHistoryRepository applicationStatusHistoryRepository;
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
                                ApplicationStatusHistoryRepository applicationStatusHistoryRepository,
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
        this.applicationStatusHistoryRepository = applicationStatusHistoryRepository;
        this.interviewSessionRepository = interviewSessionRepository;
        this.interviewRecordRepository = interviewRecordRepository;
        this.interviewAnswerAssetRepository = interviewAnswerAssetRepository;
        this.interviewAssetSectionRepository = interviewAssetSectionRepository;
        this.communicationTemplateRepository = communicationTemplateRepository;
        this.communicationDraftRepository = communicationDraftRepository;
        this.aiConsentRepository = aiConsentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取导出负载（供下载；不记日志，避免用户数据进入日志）。
     *
     * <p>事务边界**只覆盖 DB 读取**：序列化刻意留在事务外（见 {@link #writePayloadAsJson}），
     * 连接不会在 JSON 生成期间被占用。实体之间没有 JPA 关联，故返回后以分离态序列化是安全的。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> loadExportPayload(Long userId) {
        return buildPayload(userId);
    }

    /**
     * 把导出负载**流式**写入输出流（不构造整份 String / 字节数组）。
     *
     * <p>账号数据无体积上限，整份缓冲意味着峰值堆 ≈ 2× 响应体（先构造 JSON 字符串、
     * 再由消息转换器编码成字节数组）：实测 12.2MB 的响应在 {@code -Xmx128m} 上直接
     * {@code OutOfMemoryError}（6.1MB 则可完成）。流式写出的堆占用为常数级。
     */
    public void writePayloadAsJson(Map<String, Object> payload, OutputStream out) throws IOException {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, payload);
        } catch (JsonProcessingException exception) {
            // 映射失败（而非流写失败）才包装成业务错误：流已开始写出时无法再改写响应
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
        payload.put("careerMaterials", careerMaterialRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId));
        payload.put("jobDescriptions", jobDescriptionRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        payload.put("applications", applicationNodes(userId));
        payload.put("interviewSessions", interviewSessionNodes(userId));
        payload.put("interviewAnswerAssets", interviewAnswerAssetNodes(userId));
        payload.put("communicationTemplates", communicationTemplateRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        payload.put("communicationDrafts", communicationDraftRepository.findByUserIdOrderByCreatedAtDesc(userId));
        payload.put("aiConsents", aiConsentRepository.findByUserIdOrderByCreatedAtDesc(userId));
        payload.put("source", EXPORT_SOURCE);
        injectStableIds(payload);
        payload.put("manifest", buildManifest(payload));
        return payload;
    }

    /**
     * 给每条记录注入**稳定记录 ID**（R37）。
     *
     * <p>形如 {@code web:resume:123}；嵌套记录带父前缀（{@code web:resume:123:versions:456}），
     * 保证不同来源、不同层级的主键不会互相撞号 —— 桌面端正是靠它判断「这条是不是上次导入的那条」。
     */
    private void injectStableIds(Map<String, Object> payload) {
        for (String collection : EXPORT_COLLECTIONS) {
            Object value = payload.get(collection);
            if (!(value instanceof List<?> list)) {
                continue;
            }
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> raw)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> record = (Map<String, Object>) raw;
                Object id = record.get("id");
                if (id == null) {
                    continue;
                }
                record.put("stableId", EXPORT_SOURCE + ":" + collection + ":" + id);
                if ("resumes".equals(collection)) {
                    injectChildStableIds(record, "versions");
                } else if ("applications".equals(collection)) {
                    injectChildStableIds(record, "statusHistory");
                }
            }
        }
    }

    private void injectChildStableIds(Map<String, Object> parent, String childCollection) {
        Object children = parent.get(childCollection);
        if (!(children instanceof List<?> list)) {
            return;
        }
        String parentStableId = String.valueOf(parent.get("stableId"));
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> child = (Map<String, Object>) raw;
            Object id = child.get("id");
            if (id != null) {
                child.put("stableId", parentStableId + ":" + childCollection + ":" + id);
            }
        }
    }

    /**
     * 包的清单（R37）：格式版本、来源、记录计数，以及每类记录的稳定 ID 列表。
     * 桌面端导入前先校验它 —— 没有清单的包无法判断「重复导入」与「同 ID 冲突」。
     */
    private Map<String, Object> buildManifest(Map<String, Object> payload) {
        Map<String, Object> collections = new LinkedHashMap<>();
        int total = 0;
        for (String collection : EXPORT_COLLECTIONS) {
            Object value = payload.get(collection);
            List<?> list = value instanceof List<?> l ? l : List.of();
            List<String> stableIds = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> raw && raw.get("stableId") != null) {
                    stableIds.add(String.valueOf(raw.get("stableId")));
                }
            }
            collections.put(collection, Map.of("count", list.size(), "stableIds", stableIds));
            total += list.size();
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("packageId", EXPORT_SOURCE + "-export-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        manifest.put("source", EXPORT_SOURCE);
        manifest.put("recordCount", total);
        manifest.put("collections", collections);
        return manifest;
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

    /**
     * Export applications with their immutable status timeline. The timeline is
     * workspace data, not a transient task, and must survive web-to-desktop transfer.
     */
    private List<ObjectNode> applicationNodes(Long userId) {
        List<ApplicationRecord> applications = applicationRecordRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (applications.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ApplicationStatusHistory>> historyByApplicationId =
                applicationStatusHistoryRepository
                        .findByApplicationRecordIdInOrderByApplicationRecordIdAscChangedAtAscIdAsc(
                                applications.stream().map(ApplicationRecord::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(ApplicationStatusHistory::getApplicationRecordId));
        return applications.stream()
                .map(application -> attachChildren(objectMapper.valueToTree(application), "statusHistory",
                        historyByApplicationId.getOrDefault(application.getId(), List.of())))
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

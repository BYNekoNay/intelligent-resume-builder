package com.intelligentresume.personalprofile.service;

import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.personalprofile.domain.PersonalProfile;
import com.intelligentresume.personalprofile.dto.PersonalProfileRequest;
import com.intelligentresume.personalprofile.dto.PersonalProfileResponse;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 个人档案：一人一份的「我是谁 + 我想找什么」。
 *
 * <p>在链路里的位置：前端档案页 → {@code PersonalProfileController} → 本类。
 * 读侧三个来源：档案表本身、简历当前版本（importSuggestion 反推建议）、
 * 用户表（upsert 前的行锁）。写侧产出给生成链路：{@code JobGenerationService}
 * 合并联系方式进最终简历。
 *
 * <p>改坏它用户会看到什么：丢了行锁，用户开两个标签页首次保存档案会撞唯一键报 409；
 * 联系方式若被塞进提示词，用户隐私（姓名/电话/住址）会发给外部模型。
 *
 * <h2>这里存的东西不会发给模型</h2>
 * 档案含姓名、邮箱、电话、住址。它们在生成简历时由服务端合并
 * （{@code JobGenerationService#mergePersonalProfile}），**不进入任何提示词**。
 * 一旦有人在本类加一个"顺手导出给 AI"的方法，整条隐私边界就被绕过了 ——
 * 需要给模型看的内容请走 {@code CareerMaterialAiSnapshotSanitizer} 那条路。
 *
 * <h2>upsert 为什么要先锁用户行</h2>
 * 「先查有没有、没有再插」在并发下会两个请求都判定不存在，然后撞唯一键只能报 409。
 * 用 {@code findByIdForUpdate} 串行化同一用户，把冲突挡在业务层。
 *
 * <h2>importSuggestion 只读不写</h2>
 * 从简历当前版本的 {@code basics} / {@code objective} 抽候选值，**仅供用户确认**；
 * 本方法不落库、不做字段合并，也不校验用户后来改了什么。
 */
@Service
public class PersonalProfileService {

    private final PersonalProfileRepository profileRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository versionRepository;
    private final UserRepository userRepository;

    public PersonalProfileService(PersonalProfileRepository profileRepository,
                                  ResumeRepository resumeRepository,
                                  ResumeVersionRepository versionRepository,
                                  UserRepository userRepository) {
        this.profileRepository = profileRepository;
        this.resumeRepository = resumeRepository;
        this.versionRepository = versionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PersonalProfileResponse get(Long userId) {
        return profileRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(PersonalProfileResponse::empty);
    }

    @Transactional
    public PersonalProfileResponse upsert(PersonalProfileRequest request, Long userId) {
        // 首次 upsert 的并发保护（ideation #70）。步骤：①先锁**用户行**（悲观锁 = 直接锁住
        // 这一行，别的请求等着）——为什么锁用户行而不是档案行？因为首次创建时档案还不存在，
        // 没有行可锁；锁用户行把同一用户的「查后插」串行化，第二个请求会等到第一个插完。
        // ②查档案，没有就新建；③逐字段覆盖保存。
        // 唯一键冲突仍由全局 handler 兜底映射 40901。
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        PersonalProfile profile = profileRepository.findByUserId(userId).orElseGet(() -> {
            PersonalProfile created = new PersonalProfile();
            created.setUserId(userId);
            return created;
        });
        profile.setFullName(normalize(request.fullName()));
        profile.setEmail(normalize(request.email()));
        profile.setPhone(normalize(request.phone()));
        profile.setLocation(normalize(request.location()));
        profile.setWebsite(normalize(request.website()));
        profile.setProfileSummary(normalize(request.profileSummary()));
        profile.setTargetRoleTitles(normalizeList(request.targetRoleTitles()));
        profile.setTargetSeniority(normalize(request.targetSeniority()));
        profile.setTargetIndustries(normalizeList(request.targetIndustries()));
        profile.setTargetWorkPreferences(normalizeList(request.targetWorkPreferences()));
        profile.setCareerPositioningSummary(normalize(request.careerPositioningSummary()));
        return toResponse(profileRepository.save(profile));
    }

    @Transactional(readOnly = true)
    public PersonalProfileResponse importSuggestion(Long resumeId, Long userId) {
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
        if (resume.getCurrentVersionId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "简历没有当前版本");
        }
        ResumeVersion version = versionRepository
                .findByIdAndResumeId(resume.getCurrentVersionId(), resume.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历当前版本不存在"));

        Map<String, Object> resumeJson = version.getResumeJson();
        Object basicsValue = resumeJson == null ? null : resumeJson.get("basics");
        if (!(basicsValue instanceof Map<?, ?> basics)) {
            return PersonalProfileResponse.empty();
        }

        Map<?, ?> objective = resumeJson.get("objective") instanceof Map<?, ?> value ? value : Map.of();
        return new PersonalProfileResponse(
                firstText(basics, "name", "fullName"),
                firstText(basics, "email"),
                firstText(basics, "phone"),
                extractLocation(basics.get("location")),
                extractWebsite(basics),
                firstText(basics, "summary", "profileSummary"),
                extractList(objective.get("targetRole")),
                firstText(objective, "targetSeniority", "seniority"),
                extractList(objective.get("targetIndustry")),
                extractList(objective.get("location")),
                firstText(objective, "summary", "objectiveSummary")
        );
    }

    private PersonalProfileResponse toResponse(PersonalProfile profile) {
        return new PersonalProfileResponse(profile.getFullName(), profile.getEmail(), profile.getPhone(),
                profile.getLocation(), profile.getWebsite(), profile.getProfileSummary(),
                profile.getTargetRoleTitles(), profile.getTargetSeniority(), profile.getTargetIndustries(),
                profile.getTargetWorkPreferences(), profile.getCareerPositioningSummary());
    }

    private String firstText(Map<?, ?> values, String... keys) {
        for (String key : keys) {
            String value = normalize(values.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String extractLocation(Object value) {
        String direct = normalize(value);
        if (direct != null) {
            return direct;
        }
        if (!(value instanceof Map<?, ?> location)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String key : List.of("address", "city", "region", "countryCode")) {
            String part = normalize(location.get(key));
            if (part != null) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private String extractWebsite(Map<?, ?> basics) {
        String direct = firstText(basics, "website", "url");
        if (direct != null) {
            return direct;
        }
        Object profiles = basics.get("profiles");
        if (profiles instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> profile) {
                    String url = firstText(profile, "url", "website");
                    if (url != null) {
                        return url;
                    }
                }
            }
        }
        return null;
    }

    private List<String> extractList(Object value) {
        if (value instanceof Iterable<?> values) {
            List<String> result = new ArrayList<>();
            for (Object item : values) {
                String normalized = normalize(item);
                if (normalized != null && !result.contains(normalized)) {
                    result.add(normalized);
                }
            }
            return result.isEmpty() ? null : List.copyOf(result);
        }
        String normalized = normalize(value);
        return normalized == null ? null : List.of(normalized);
    }

    private String normalize(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> normalized = values.stream()
                .map(this::normalize)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return normalized.isEmpty() ? null : normalized;
    }
}

package com.intelligentresume.personalprofile.service;

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

@Service
public class PersonalProfileService {

    private final PersonalProfileRepository profileRepository;
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository versionRepository;

    public PersonalProfileService(PersonalProfileRepository profileRepository,
                                  ResumeRepository resumeRepository,
                                  ResumeVersionRepository versionRepository) {
        this.profileRepository = profileRepository;
        this.resumeRepository = resumeRepository;
        this.versionRepository = versionRepository;
    }

    @Transactional(readOnly = true)
    public PersonalProfileResponse get(Long userId) {
        return profileRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(PersonalProfileResponse::empty);
    }

    @Transactional
    public PersonalProfileResponse upsert(PersonalProfileRequest request, Long userId) {
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

        Object basicsValue = version.getResumeJson() == null ? null : version.getResumeJson().get("basics");
        if (!(basicsValue instanceof Map<?, ?> basics)) {
            return PersonalProfileResponse.empty();
        }
        return new PersonalProfileResponse(
                firstText(basics, "name", "fullName"),
                firstText(basics, "email"),
                firstText(basics, "phone"),
                extractLocation(basics.get("location")),
                extractWebsite(basics),
                firstText(basics, "summary", "profileSummary"),
                null, null, null, null, null
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

package com.intelligentresume.ai.selection.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.personalprofile.domain.PersonalProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实体 → AI 任务快照 JSON 的映射。
 *
 * <p>为什么单独成类：这些映射决定 worker 重建实体后拿到的字段，也就决定了 prompt 的实际内容 ——
 * 预览（{@code buildGenerationInput} 干跑）与 confirm（落库）必须产出**完全相同**的映射结果，
 * 因此只留一份实现，且只做纯字段搬运（不含业务判断）。
 */
final class MaterialSelectionSnapshotMapper {

    private MaterialSelectionSnapshotMapper() {
    }

    static Map<String, Object> of(JobDescription job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", job.getId());
        value.put("title", job.getTitle());
        value.put("companyName", job.getCompanyName());
        value.put("jdText", job.getJdText());
        value.put("parsedKeywordsJson", job.getParsedKeywordsJson());
        return value;
    }

    static Map<String, Object> of(CareerMaterial material) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", material.getId());
        value.put("materialType", material.getMaterialType().name());
        value.put("title", material.getTitle());
        value.put("contentJson", material.getContentJson());
        value.put("sourceText", material.getSourceText());
        value.put("usagePreference", material.getUsagePreference().name());
        return value;
    }

    /** 含联系方式：是否发给模型由 {@code _includeContact} / prompt 的 profile 段决定，映射层不改内容。 */
    static Map<String, Object> of(PersonalProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("fullName", profile.getFullName());
        value.put("email", profile.getEmail());
        value.put("phone", profile.getPhone());
        value.put("location", profile.getLocation());
        value.put("website", profile.getWebsite());
        value.put("profileSummary", profile.getProfileSummary());
        value.put("targetRoleTitles", profile.getTargetRoleTitles());
        value.put("targetSeniority", profile.getTargetSeniority());
        value.put("targetIndustries", profile.getTargetIndustries());
        value.put("targetWorkPreferences", profile.getTargetWorkPreferences());
        value.put("careerPositioningSummary", profile.getCareerPositioningSummary());
        return value;
    }
}

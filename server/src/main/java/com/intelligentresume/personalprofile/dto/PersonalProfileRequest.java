package com.intelligentresume.personalprofile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 个人档案的整份保存请求：字段与实体一一对应，全字段可选（允许只改一部分语义由 service 决定）。
 *
 * <p>长度上限与实体列宽对齐；列表字段统一最多 20 项、每项最长 128 字符——
 * 放宽时记得同步实体列宽与前端表单限制。</p>
 */
public record PersonalProfileRequest(
        @Size(max = 128) String fullName,
        @Email @Size(max = 128) String email,
        @Size(max = 64) String phone,
        @Size(max = 255) String location,
        @Size(max = 512) String website,
        @Size(max = 10000) String profileSummary,
        @Size(max = 20) List<@Size(max = 128) String> targetRoleTitles,
        @Size(max = 128) String targetSeniority,
        @Size(max = 20) List<@Size(max = 128) String> targetIndustries,
        @Size(max = 20) List<@Size(max = 128) String> targetWorkPreferences,
        @Size(max = 10000) String careerPositioningSummary
) {}

package com.intelligentresume.personalprofile.dto;

import java.util.List;

/**
 * 个人档案的读取响应：字段与 {@link PersonalProfileRequest} 对应，不含任何服务端合成内容。
 * {@link #empty()} 表示「还没有档案」，前端据此渲染空态而不是报错。
 */
public record PersonalProfileResponse(
        String fullName,
        String email,
        String phone,
        String location,
        String website,
        String profileSummary,
        List<String> targetRoleTitles,
        String targetSeniority,
        List<String> targetIndustries,
        List<String> targetWorkPreferences,
        String careerPositioningSummary
) {
    public static PersonalProfileResponse empty() {
        return new PersonalProfileResponse(null, null, null, null, null, null,
                null, null, null, null, null);
    }
}

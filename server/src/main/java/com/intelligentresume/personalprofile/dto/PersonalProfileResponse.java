package com.intelligentresume.personalprofile.dto;

import java.util.List;

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

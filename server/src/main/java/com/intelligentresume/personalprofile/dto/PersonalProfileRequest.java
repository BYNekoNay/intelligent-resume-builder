package com.intelligentresume.personalprofile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.List;

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

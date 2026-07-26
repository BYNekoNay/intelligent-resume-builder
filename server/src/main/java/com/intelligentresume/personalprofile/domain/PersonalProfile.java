package com.intelligentresume.personalprofile.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "personal_profile")
public class PersonalProfile extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "full_name", length = 128)
    private String fullName;

    @Column(name = "email", length = 128)
    private String email;

    @Column(name = "phone", length = 64)
    private String phone;

    @Column(name = "location", length = 255)
    private String location;

    @Column(name = "website", length = 512)
    private String website;

    @Column(name = "profile_summary", columnDefinition = "TEXT")
    private String profileSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_role_titles", columnDefinition = "json")
    private List<String> targetRoleTitles;

    @Column(name = "target_seniority", length = 128)
    private String targetSeniority;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_industries", columnDefinition = "json")
    private List<String> targetIndustries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_work_preferences", columnDefinition = "json")
    private List<String> targetWorkPreferences;

    @Column(name = "career_positioning_summary", columnDefinition = "TEXT")
    private String careerPositioningSummary;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getProfileSummary() { return profileSummary; }
    public void setProfileSummary(String profileSummary) { this.profileSummary = profileSummary; }
    public List<String> getTargetRoleTitles() { return targetRoleTitles; }
    public void setTargetRoleTitles(List<String> targetRoleTitles) { this.targetRoleTitles = targetRoleTitles; }
    public String getTargetSeniority() { return targetSeniority; }
    public void setTargetSeniority(String targetSeniority) { this.targetSeniority = targetSeniority; }
    public List<String> getTargetIndustries() { return targetIndustries; }
    public void setTargetIndustries(List<String> targetIndustries) { this.targetIndustries = targetIndustries; }
    public List<String> getTargetWorkPreferences() { return targetWorkPreferences; }
    public void setTargetWorkPreferences(List<String> targetWorkPreferences) { this.targetWorkPreferences = targetWorkPreferences; }
    public String getCareerPositioningSummary() { return careerPositioningSummary; }
    public void setCareerPositioningSummary(String careerPositioningSummary) { this.careerPositioningSummary = careerPositioningSummary; }
}

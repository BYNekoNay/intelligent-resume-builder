package com.intelligentresume.personalprofile.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 个人档案实体（表 {@code personal_profile}）：一人一份的「我是谁 + 我想找什么」。
 *
 * <p><b>隐私红线</b>：{@code fullName/email/phone/location} 属联系方式，生成简历时由服务端合并、
 * <b>绝不进入任何 AI 提示词</b>（见 {@code PersonalProfileService} 头部说明）。</p>
 *
 * <p>字段分三组：基础身份（fullName~website）、求职意向（target* 四项 + 两个 summary）、
 * 其中四个 target 集合列是 JSON 列；修改本类字段要同步 {@link PersonalProfileRequest}/
 * {@link PersonalProfileResponse} 与 Flyway 迁移。</p>
 */
@Entity
@Table(name = "personal_profile")
public class PersonalProfile extends BaseEntity {

    /** 全局唯一：一个用户最多一份档案，service 层用「锁用户行再 upsert」挡并发唯一键冲突。 */
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

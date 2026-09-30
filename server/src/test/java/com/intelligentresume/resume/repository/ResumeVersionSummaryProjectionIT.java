package com.intelligentresume.resume.repository;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.dto.ResumeVersionSummary;
import com.intelligentresume.resume.dto.SaveVersionRequest;
import com.intelligentresume.resume.service.ResumeVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本历史摘要投影契约（ideation #50）。
 *
 * <p>投影只取元数据列（不加载 resume_json / generation_context）；
 * {@code template_code} 是 V32 派生列：新版本写入时派生，历史行（列为 NULL）由读路径惰性回填。
 */
@SpringBootTest
@ActiveProfiles("test")
class ResumeVersionSummaryProjectionIT {

    @Autowired private UserRepository userRepository;
    @Autowired private ResumeRepository resumeRepository;
    @Autowired private ResumeVersionService versionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("列表返回派生 template_code；历史行（列为 NULL）被惰性回填并写回；归档路径同样生效")
    void summaryProjection_backfillsLegacyTemplateCode() {
        Long userId = createUser("version_summary_projection");
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle("Projection resume");
        resumeRepository.save(resume);

        Long firstVersionId = versionService.save(resume.getId(), request("modern"), userId).id();
        Long secondVersionId = versionService.save(resume.getId(), request("minimal"), userId).id();
        // 写入路径即派生（不依赖读路径回填）
        assertEquals("modern", jdbcTemplate.queryForObject(
                "SELECT template_code FROM resume_version WHERE id = ?", String.class, firstVersionId));
        versionService.archive(resume.getId(), secondVersionId, userId);

        List<ResumeVersionSummary> active = versionService.listByResume(resume.getId(), false, userId);
        assertEquals(1, active.size());
        assertEquals(firstVersionId, active.get(0).id());
        assertEquals("modern", active.get(0).templateCode(), "新版本写入时应已派生模板列");

        List<ResumeVersionSummary> archived = versionService.listByResume(resume.getId(), true, userId);
        assertEquals(1, archived.size());
        assertEquals(secondVersionId, archived.get(0).id());
        assertEquals("minimal", archived.get(0).templateCode());
        assertTrue(archived.get(0).archivedAt() != null);

        // 模拟 V32 之前的历史行：派生列为 NULL
        jdbcTemplate.update("UPDATE resume_version SET template_code = NULL WHERE id = ?", firstVersionId);

        List<ResumeVersionSummary> reread = versionService.listByResume(resume.getId(), false, userId);

        assertEquals("modern", reread.get(0).templateCode(), "历史行应按 resume_json 惰性派生");
        assertEquals("modern", jdbcTemplate.queryForObject(
                        "SELECT template_code FROM resume_version WHERE id = ?", String.class, firstVersionId),
                "首次列表读取后派生列应已写回（下次列表无需再读 resume_json）");
    }

    private SaveVersionRequest request(String templateCode) {
        return new SaveVersionRequest(
                Map.of("basics", Map.of("name", "Alice"), "template", Map.of("code", templateCode)),
                ResumeSourceType.MANUAL, null);
    }

    private Long createUser(String usernamePrefix) {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(usernamePrefix + "_" + suffix);
        user.setEmail(usernamePrefix + "-" + suffix + "@example.test");
        user.setPasswordHash("hash");
        userRepository.save(user);
        return user.getId();
    }
}
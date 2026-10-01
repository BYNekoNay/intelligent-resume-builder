package com.intelligentresume.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分档清扫的取数与删除（决策 D2 阶段 1）。
 *
 * <p><b>引用判定即「候选筛选」</b>：每张受管资源一条 SQL，用逐条 {@code NOT EXISTS} 排除所有引用者。
 * 这样「无引用」由数据库判定，不引入额外的引用登记表（避免双写漂移）。
 *
 * <p>引用清单来自**实测**（`server/src/test/resources/mysql57/v19-schema.sql` 的表定义 +
 * 全部迁移），见方案 `docs/plans/2026-10-01-001-data-retention-tiered-purge.md` §1.1：
 * <ul>
 *   <li>{@code resume_version} 被 11 处引用（含自引用 {@code restored_from_version_id}）；</li>
 *   <li>{@code career_material} 被 2 处引用。</li>
 * </ul>
 *
 * <p>删除语句一律带 {@code deleted_at IS NOT NULL} —— 作为**二次保护**：
 * 即使候选查询被改坏，也不会误删未软删的活数据。
 */
@Component
public class RetentionPurgeRepository {

    /** 超期且无任何引用的软删简历版本。（package-private：供完整性测试读取，见 RetentionPurgeRepositorySchemaTest） */
    static final String PURGEABLE_RESUME_VERSIONS = """
            SELECT rv.id FROM resume_version rv
            WHERE rv.deleted_at IS NOT NULL AND rv.deleted_at <= ?
              AND NOT EXISTS (SELECT 1 FROM resume t WHERE t.current_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM resume_material_reference t WHERE t.resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM match_result t WHERE t.resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM ai_task t WHERE t.result_resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM export_task t WHERE t.resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM inline_optimization_record t WHERE t.resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM ats_check_result t WHERE t.resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM application_record t WHERE t.resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM communication_draft t WHERE t.resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM interview_session t WHERE t.resume_version_id = rv.id)
              AND NOT EXISTS (SELECT 1 FROM resume_version t WHERE t.restored_from_version_id = rv.id)
            ORDER BY rv.id
            LIMIT ?
            """;

    /** 超期且无任何引用的软删职业资料。（package-private：同上） */
    static final String PURGEABLE_CAREER_MATERIALS = """
            SELECT cm.id FROM career_material cm
            WHERE cm.deleted_at IS NOT NULL AND cm.deleted_at <= ?
              AND NOT EXISTS (SELECT 1 FROM resume_material_reference t WHERE t.material_id = cm.id)
              AND NOT EXISTS (SELECT 1 FROM interview_asset_section t WHERE t.material_id = cm.id)
            ORDER BY cm.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public RetentionPurgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 删除语句：一律带 {@code deleted_at IS NOT NULL}，作为**二次保护**（见类注释）。 */
    static final String DELETE_RESUME_VERSION =
            "DELETE FROM resume_version WHERE id = ? AND deleted_at IS NOT NULL";

    static final String DELETE_CAREER_MATERIAL =
            "DELETE FROM career_material WHERE id = ? AND deleted_at IS NOT NULL";

    public List<Long> findPurgeableResumeVersions(LocalDateTime cutoff, int limit) {
        return jdbc.queryForList(PURGEABLE_RESUME_VERSIONS, Long.class, cutoff, limit);
    }

    public List<Long> findPurgeableCareerMaterials(LocalDateTime cutoff, int limit) {
        return jdbc.queryForList(PURGEABLE_CAREER_MATERIALS, Long.class, cutoff, limit);
    }

    /** @return 实际删除行数（0 表示该行已不满足条件——未被删或已被并发处理）。 */
    public int deleteResumeVersion(Long id) {
        return jdbc.update(DELETE_RESUME_VERSION, id);
    }

    public int deleteCareerMaterial(Long id) {
        return jdbc.update(DELETE_CAREER_MATERIAL, id);
    }
}

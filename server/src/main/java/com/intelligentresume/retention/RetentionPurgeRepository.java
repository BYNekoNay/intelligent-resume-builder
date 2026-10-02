package com.intelligentresume.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分档清扫的取数与处置（决策 D2 阶段 1 A 档 + 阶段 2 B 档）。
 *
 * <p><b>引用判定即「候选筛选」</b>：每张受管资源一对 SQL —— {@code NOT EXISTS} 版取「无引用者」
 * （A 档：物理删除），{@code EXISTS} 版取「有引用者」（B 档：转最小快照）。两极必须覆盖**同一份**
 * 引用者清单，由 {@code RetentionPurgeRepositorySchemaTest} 的门禁守护（防两处清单漂移）。
 *
 * <p>引用清单来自**实测**（`server/src/test/resources/mysql57/v19-schema.sql` 的表定义 +
 * 全部迁移），见方案 `docs/plans/2026-10-01-001-data-retention-tiered-purge.md` §1.1：
 * <ul>
 *   <li>{@code resume_version} 被 11 处引用（含自引用 {@code restored_from_version_id}）；</li>
 *   <li>{@code career_material} 被 2 处引用。</li>
 * </ul>
 *
 * <p>写语句一律带两条保护：① {@code deleted_at IS NOT NULL}（即使候选查询被改坏，也不会动活数据）；
 * ② {@code id = ?} 逐行（不做无界更新）。
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

    /** 超期且**存在**引用的软删简历版本（B 档）。极性反转自 {@link #PURGEABLE_RESUME_VERSIONS}。 */
    static final String SNAPSHOTTABLE_RESUME_VERSIONS = """
            SELECT rv.id FROM resume_version rv
            WHERE rv.deleted_at IS NOT NULL AND rv.deleted_at <= ?
              AND (EXISTS (SELECT 1 FROM resume t WHERE t.current_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM resume_material_reference t WHERE t.resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM match_result t WHERE t.resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM ai_task t WHERE t.result_resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM export_task t WHERE t.resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM inline_optimization_record t WHERE t.resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM ats_check_result t WHERE t.resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM application_record t WHERE t.resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM communication_draft t WHERE t.resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM interview_session t WHERE t.resume_version_id = rv.id)
                OR EXISTS (SELECT 1 FROM resume_version t WHERE t.restored_from_version_id = rv.id))
            ORDER BY rv.id
            LIMIT ?
            """;

    /** 超期且**存在**引用的软删职业资料（B 档）。极性反转自 {@link #PURGEABLE_CAREER_MATERIALS}。 */
    static final String SNAPSHOTTABLE_CAREER_MATERIALS = """
            SELECT cm.id FROM career_material cm
            WHERE cm.deleted_at IS NOT NULL AND cm.deleted_at <= ?
              AND (EXISTS (SELECT 1 FROM resume_material_reference t WHERE t.material_id = cm.id)
                OR EXISTS (SELECT 1 FROM interview_asset_section t WHERE t.material_id = cm.id))
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

    /**
     * 简历版本快照化：正文/上下文/摘要全部丢弃，只留「已被清空」这一事实。
     *
     * <p>用 {@code JSON_OBJECT(...)} 而非绑定字符串 —— 该函数在 MySQL 与 H2 都存在，
     * 无需处理「字符→JSON」的方言差异（{@code CAST(x AS JSON)} vs {@code x FORMAT JSON}）。
     * 内容为**常量** ⇒ 重复执行结果一致（幂等）。
     */
    static final String SNAPSHOT_RESUME_VERSION = """
            UPDATE resume_version
            SET resume_json = JSON_OBJECT('__purged', TRUE),
                generation_context = NULL,
                optimization_summary = NULL
            WHERE id = ? AND deleted_at IS NOT NULL
            """;

    /**
     * 职业资料快照化：内容 JSON 换成常量标记，并**一并清空 {@code source_text}**
     * （MEDIUMTEXT，导入时的原文 —— 方案 §4 初版漏了这一列，它与 `content_json` 一样是 PII 载体）。
     * 同样用 {@code JSON_OBJECT(...)}，避开「字符→JSON」的方言差异。
     */
    static final String SNAPSHOT_CAREER_MATERIAL = """
            UPDATE career_material
            SET content_json = JSON_OBJECT('__purged', TRUE),
                source_text = NULL
            WHERE id = ? AND deleted_at IS NOT NULL
            """;

    public List<Long> findPurgeableResumeVersions(LocalDateTime cutoff, int limit) {
        return jdbc.queryForList(PURGEABLE_RESUME_VERSIONS, Long.class, cutoff, limit);
    }

    public List<Long> findPurgeableCareerMaterials(LocalDateTime cutoff, int limit) {
        return jdbc.queryForList(PURGEABLE_CAREER_MATERIALS, Long.class, cutoff, limit);
    }

    public List<Long> findSnapshottableResumeVersions(LocalDateTime cutoff, int limit) {
        return jdbc.queryForList(SNAPSHOTTABLE_RESUME_VERSIONS, Long.class, cutoff, limit);
    }

    public List<Long> findSnapshottableCareerMaterials(LocalDateTime cutoff, int limit) {
        return jdbc.queryForList(SNAPSHOTTABLE_CAREER_MATERIALS, Long.class, cutoff, limit);
    }

    /** @return 实际删除行数（0 表示该行已不满足条件——未被删或已被并发处理）。 */
    public int deleteResumeVersion(Long id) {
        return jdbc.update(DELETE_RESUME_VERSION, id);
    }

    public int deleteCareerMaterial(Long id) {
        return jdbc.update(DELETE_CAREER_MATERIAL, id);
    }

    /** @return 实际快照化行数（0 表示该行已不再满足条件）。 */
    public int snapshotResumeVersion(Long id) {
        return jdbc.update(SNAPSHOT_RESUME_VERSION, id);
    }

    public int snapshotCareerMaterial(Long id) {
        return jdbc.update(SNAPSHOT_CAREER_MATERIAL, id);
    }
}

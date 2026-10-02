package com.intelligentresume.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 账户级联硬删（决策 D2 阶段 3）。
 *
 * <p>删号撤销窗口结束后，把该用户的**全部**数据按外键依赖序（先引用者、后被引用者）物理删除，
 * 最后删除 {@code user} 行本身。与 {@link RetentionPurgeRepository} 的分档清扫语义不同：
 * 账户删除没有「被引用者保留」分支 —— 引用链整体同属被删用户，一并消失。
 *
 * <p>清单来源：全部迁移的 23 张表逐张核对（2026-10-02）——
 * <ul>
 *   <li>18 张含 {@code user_id}：直接 {@code WHERE user_id = ?}；</li>
 *   <li>4 张无 {@code user_id} 经链到达：{@code resume_version}（经 resume）、
 *       {@code resume_material_reference} 与 {@code match_result}（经 resume_version）、
 *       {@code interview_record}（经 interview_session）—— 用 {@code IN (SELECT …)}；</li>
 *   <li>{@code communication_template} 有系统种子行（{@code user_id IS NULL}），条件删除只会
 *       命中该用户自建模板；</li>
 *   <li>覆盖完整性由 {@code AccountPurgeRepositorySchemaTest} 从迁移反查守护（漏一张即红）；
 *       H2 外键真实生效，{@code AccountPurgeIntegrationIT} 以真实行实证顺序正确。</li>
 * </ul>
 *
 * <p>全部语句幂等（已删行再删 0 行），因此 {@code PARTIAL_FAILED} 的任务可安全重入。
 */
@Component
public class AccountPurgeRepository {

    /** 每用户的级联删除语句，顺序 = 外键依赖序（先引用者、后被引用者）。 */
    public static final List<String> PURGE_STATEMENTS = List.of(
            // —— 经 resume_version / resume / interview_session 间接关联的叶子表 ——
            """
            DELETE FROM resume_material_reference
            WHERE resume_version_id IN (
                SELECT rv.id FROM resume_version rv JOIN resume r ON rv.resume_id = r.id WHERE r.user_id = ?)
            """,
            """
            DELETE FROM match_result
            WHERE resume_version_id IN (
                SELECT rv.id FROM resume_version rv JOIN resume r ON rv.resume_id = r.id WHERE r.user_id = ?)
            """,
            // —— 含 user_id 的表，先删「引用 resume_version 的」，再删 resume_version 本身 ——
            "DELETE FROM ats_check_result WHERE user_id = ?",
            "DELETE FROM ai_task WHERE user_id = ?",
            "DELETE FROM export_task WHERE user_id = ?",
            "DELETE FROM application_record WHERE user_id = ?",
            "DELETE FROM inline_optimization_record WHERE user_id = ?",
            "DELETE FROM communication_draft WHERE user_id = ?",
            "DELETE FROM communication_template WHERE user_id = ?",
            "DELETE FROM ai_consent WHERE user_id = ?",
            "DELETE FROM material_resume_generation WHERE user_id = ?",
            // interview 链：attempt → record → asset_section → answer_asset → session
            //（asset_section 引用 answer_asset 与 career_material，作为引用方先删）
            "DELETE FROM interview_ai_attempt WHERE user_id = ?",
            """
            DELETE FROM interview_record
            WHERE session_id IN (SELECT id FROM interview_session WHERE user_id = ?)
            """,
            "DELETE FROM interview_asset_section WHERE user_id = ?",
            "DELETE FROM interview_answer_asset WHERE user_id = ?",
            "DELETE FROM interview_session WHERE user_id = ?",
            "DELETE FROM career_material WHERE user_id = ?",
            "DELETE FROM resume_version WHERE resume_id IN (SELECT id FROM resume WHERE user_id = ?)",
            "DELETE FROM resume WHERE user_id = ?",
            "DELETE FROM job_description WHERE user_id = ?",
            "DELETE FROM personal_profile WHERE user_id = ?",
            "DELETE FROM auth_session WHERE user_id = ?"
    );

    private final JdbcTemplate jdbcTemplate;

    public AccountPurgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 级联删除该用户的全部业务数据（不含 user 行与 account_deletion_job 行）。 */
    public void purgeUserData(Long userId) {
        for (String sql : PURGE_STATEMENTS) {
            jdbcTemplate.update(sql, userId);
        }
    }

    /** 删除 user 行本身（调用方已在同一事务内完成业务数据清理）。 */
    public void deleteUser(Long userId) {
        jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
    }
}

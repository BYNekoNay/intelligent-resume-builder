package com.intelligentresume.ats.domain;

import com.intelligentresume.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;

/**
 * ATS 检查结果（表 {@code ats_check_result}）：一次"简历版本 × 岗位"的体检记录。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code (user_id, idempotency_key)} 上有唯一约束 —— {@code AtsService.check} 的并发重试依赖它，
 *       删了唯一索引会让同一请求产生两条记录。</li>
 *   <li>{@code result_json} 是**整个可变状态**（规则分、AI 结论、兜底信息、状态机字段）都塞在里面的
 *       JSON 列；修改时必须整列写回，并发写要走 {@code AtsResultStateService} 的悲观锁路径。</li>
 *   <li>{@code total_score} 只冗余了规则总分，便于排序/统计；它不随 AI 结果变化。</li>
 *   <li>{@code request_fingerprint} 是幂等键的"内容指纹"：同键不同指纹说明客户端复用了 key，服务端要拒。</li>
 * </ul>
 */
@Entity
@Table(name = "ats_check_result")
public class AtsCheckResult extends BaseEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "resume_version_id", nullable = false)
    private Long resumeVersionId;
    @Column(name = "job_description_id", nullable = false)
    private Long jobDescriptionId;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 128)
    private String requestFingerprint;
    @Column(name = "total_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalScore;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> resultJson;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public BigDecimal getTotalScore() { return totalScore; }
    public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }
    public Map<String, Object> getResultJson() { return resultJson; }
    public void setResultJson(Map<String, Object> resultJson) { this.resultJson = resultJson; }
}

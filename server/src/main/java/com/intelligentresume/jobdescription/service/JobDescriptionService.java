package com.intelligentresume.jobdescription.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.dto.*;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JD CRUD + 确定性关键词解析。
 *
 * <p>用大白话说：用户管理自己投过的岗位——存一份 JD 原文，点"解析"抽出关键词，
 * 之后生成"针对这个岗位的简历"并算关键词覆盖度都要用到这份 JD。
 *
 * <p>在链路里的位置：前端岗位页 → {@code JobDescriptionController} → 本类 →
 * {@code JobDescriptionRepository}；解析动作委托给 {@link JdKeywordParser}（纯规则、不碰库）；
 * 解析结果落库后给 ScoringService（算覆盖度）和选材快照（冻结 JD 内容）消费。
 *
 * <p>改坏它用户会看到什么：丢了"正文变了就清空解析结果"的逻辑，用户改完 JD 后
 * 覆盖度还在拿旧关键词算分，结果全错；丢了行锁检查，解析期间别人的编辑会被静默覆盖。
 *
 * <p>关键约定:
 * <ul>
 *     <li>{@code jd_text} 原文永不覆盖,即使重复调用 parse。</li>
 *     <li>{@code parsed_keywords_json} 每次重新生成(覆盖)。</li>
 *     <li>所有查询带 userId 条件,跨用户返回 NOT_FOUND。</li>
 * </ul>
 *
 * <p>实现要点：
 * <ul>
 *     <li>解析结果与 {@code jd_text} 必须同源：正文一旦变化，三列解析结果（json / at / version）
 *       会被一起清空，不能留着旧关键词继续算覆盖度；</li>
 *     <li>parse 不持锁执行解析、写回前再取行锁，用于发现"解析期间被编辑"的并发场景，
 *       冲突时返回 CONFLICT 而不是覆盖用户的修改；</li>
 *     <li>解析器是无状态纯函数（{@link JdKeywordParser}），本类只负责读写与版本落地，
 *       不要在这里补规则；</li>
 *     <li>长度上限来自 {@code app.job.jd-text.max-length}，创建与更新两条入口都要过同一道校验。</li>
 * </ul>
 */
@Service
public class JobDescriptionService {

    private static final int SUMMARY_PREVIEW_LENGTH = 120;

    private final JobDescriptionRepository repository;
    private final JdKeywordParser parser;
    private final int maxLength;

    @PersistenceContext
    private EntityManager entityManager;

    public JobDescriptionService(JobDescriptionRepository repository,
                                 JdKeywordParser parser,
                                 @Value("${app.job.jd-text.max-length:5000}") int maxLength) {
        this.repository = repository;
        this.parser = parser;
        this.maxLength = maxLength;
    }

    @Transactional
    public JobDescriptionDetail create(CreateJobDescriptionRequest req, Long userId) {
        validateJdTextLength(req.jdText());

        JobDescription jd = new JobDescription();
        jd.setUserId(userId);
        jd.setTitle(req.title());
        jd.setCompanyName(req.companyName());
        jd.setJdText(req.jdText());
        repository.save(jd);
        return toDetail(jd);
    }

    @Transactional(readOnly = true)
    public List<JobDescriptionSummary> list(Long userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public JobDescriptionDetail get(Long id, Long userId) {
        return toDetail(findOwned(id, userId));
    }

    @Transactional(readOnly = true)
    public JobDescriptionReference reference(Long id, Long userId) {
        JobDescriptionRepository.ReferenceProjection reference = repository
                .findReferenceByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "JD 不存在"));
        return new JobDescriptionReference(reference.getId(), reference.getTitle(), reference.getCompanyName());
    }

    /**
     * 更新 JD（PATCH 语义：字段为 null 表示不改）。
     *
     * <p>顺序上先判"正文是否真的变了"再决定清不清解析结果：
     * 内容相同就不清，避免用户只改标题却把已有的关键词白扔掉。
     * 注意这里只清空、不自动重解析——重解析是用户的显式动作（POST /{id}/parse）。
     *
     * <p>与创建不一致之处：更新请求的 {@code jdText} 没有 {@code @NotBlank}，
     * 传空串会原样写入（库列 non-null，但允许空串），从而触发上面的"清空解析结果"分支。
     */
    @Transactional
    public JobDescriptionDetail update(Long id, UpdateJobDescriptionRequest req, Long userId) {
        JobDescription jd = findOwned(id, userId);
        if (req.title() != null && !req.title().isBlank()) {
            jd.setTitle(req.title());
        }
        if (req.companyName() != null) {
            jd.setCompanyName(req.companyName());
        }
        if (req.jdText() != null) {
            validateJdTextLength(req.jdText());
            if (!Objects.equals(jd.getJdText(), req.jdText())) {
                jd.setParsedKeywordsJson(null);
                jd.setParsedAt(null);
                jd.setParsedVersion(null);
            }
            jd.setJdText(req.jdText());
        }
        repository.save(jd);
        return toDetail(jd);
    }

    @Transactional
    public void softDelete(Long id, Long userId) {
        JobDescription jd = findOwned(id, userId);
        jd.setDeletedAt(LocalDateTime.now());
        repository.save(jd);
    }

    /**
     * 解析关键词。步骤：①取出 JD 原文；②调 {@link JdKeywordParser} 纯内存解析（不持锁，
     * 持锁会拖长事务）；③写回前重新加行锁（悲观锁 = 直接把这行数据库行锁住，别人动不了）
     * 复查原文——若解析期间被人编辑过则抛 CONFLICT，丢弃本次结果；④把结果连同规则版本
     * 一起写回 {@code parsed_keywords_json}（覆盖旧值），{@code jd_text} 原文不动。
     * 返回完整 JobDescriptionDetail(前端契约:parseJob 返回 JobDescription)。
     *
     * <p>并发与结构说明：
     * <ul>
     *   <li>解析本身不持锁（纯 CPU，持锁会拖长事务），写回前用 {@code PESSIMISTIC_WRITE}
     *       重新读一次行；若原文已被改动则抛 CONFLICT，丢弃本次结果而不是覆盖别人的编辑；</li>
     *   <li>{@code entityManager} 为 null 时（纯单元测试未注入）跳过加锁，仅做写回；</li>
     *   <li>落库形态固定为 {@code {"version": "...", "data": {role, keywords, requirements}}}，
     *       下游 {@code ScoringService.readParsedJd} 与选材快照按 {@code data} 取键，
     *       改外层嵌套等于改存储契约；</li>
     *   <li>解析结果为空（短文本）也会照常覆盖写回，不保留上一次的成功结果。</li>
     * </ul>
     */
    @Transactional
    public JobDescriptionDetail parse(Long id, Long userId) {
        JobDescription jd = findOwned(id, userId);
        String sourceText = jd.getJdText();

        ParsedKeywordsResponse parsed = parser.parse(sourceText);

        // Parsing is intentionally performed without holding a database lock. Reacquire
        // the row lock before writing so an edit that completed while the parser ran is
        // observed and the stale result is discarded instead of overwriting the edit.
        if (entityManager != null) {
            entityManager.refresh(jd, LockModeType.PESSIMISTIC_WRITE);
        }
        if (!Objects.equals(sourceText, jd.getJdText())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "JD 原文已更新，解析结果已失效，请重新解析");
        }

        // 写回 parsed_keywords_json: {"version": "v1.0.0", "data": {...}}
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("version", JdParserRuleVersion.CURRENT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", parsed.role());
        data.put("keywords", parsed.keywords());
        data.put("requirements", parsed.requirements());
        wrapped.put("data", data);

        jd.setParsedKeywordsJson(wrapped);
        jd.setParsedAt(LocalDateTime.now());
        jd.setParsedVersion(JdParserRuleVersion.CURRENT);
        repository.save(jd);

        return toDetail(jd);
    }

    // ---- helpers ----

    private JobDescription findOwned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "JD 不存在"));
    }

    /** 长度上限只拦超长；null 放行（创建由 @NotBlank 拦，更新允许空串/缺省）。 */
    private void validateJdTextLength(String jdText) {
        if (jdText != null && jdText.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "jdText 超过长度限制 (" + maxLength + " 字符)");
        }
    }

    private JobDescriptionSummary toSummary(JobDescription jd) {
        return new JobDescriptionSummary(jd.getId(), jd.getTitle(), jd.getCompanyName(),
                previewText(jd.getJdText()), jd.getParsedAt(), jd.getUpdatedAt());
    }

    /**
     * 列表预览：连续空白压成单空格后截断，超长补 "..."（最终长度 123，不是 120）。
     * 截断按字符而非词边界，可能把英文单词切断，仅用于展示。
     */
    private String previewText(String text) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SUMMARY_PREVIEW_LENGTH
                ? normalized
                : normalized.substring(0, SUMMARY_PREVIEW_LENGTH) + "...";
    }

    private JobDescriptionDetail toDetail(JobDescription jd) {
        return new JobDescriptionDetail(jd.getId(), jd.getTitle(), jd.getCompanyName(),
                jd.getJdText(), jd.getParsedKeywordsJson(),
                jd.getParsedAt(), jd.getParsedVersion(),
                jd.getCreatedAt(), jd.getUpdatedAt());
    }
}

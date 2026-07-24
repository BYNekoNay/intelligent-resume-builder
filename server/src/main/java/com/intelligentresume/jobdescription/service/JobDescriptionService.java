package com.intelligentresume.jobdescription.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.dto.*;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JD CRUD + 确定性关键词解析。
 *
 * <p>关键约定:
 * <ul>
 *     <li>{@code jd_text} 原文永不覆盖,即使重复调用 parse。</li>
 *     <li>{@code parsed_keywords_json} 每次重新生成(覆盖)。</li>
 *     <li>所有查询带 userId 条件,跨用户返回 NOT_FOUND。</li>
 * </ul>
 */
@Service
public class JobDescriptionService {

    private final JobDescriptionRepository repository;
    private final JdKeywordParser parser;
    private final int maxLength;

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
     * 解析关键词:调 JdKeywordParser,写回 parsed_keywords_json(覆盖),jd_text 不动。
     * 返回完整 JobDescriptionDetail(前端契约:parseJob 返回 JobDescription)。
     */
    @Transactional
    public JobDescriptionDetail parse(Long id, Long userId) {
        JobDescription jd = findOwned(id, userId);

        ParsedKeywordsResponse parsed = parser.parse(jd.getJdText());

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

    private void validateJdTextLength(String jdText) {
        if (jdText != null && jdText.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "jdText 超过长度限制 (" + maxLength + " 字符)");
        }
    }

    private JobDescriptionSummary toSummary(JobDescription jd) {
        return new JobDescriptionSummary(jd.getId(), jd.getTitle(), jd.getCompanyName(), jd.getUpdatedAt());
    }

    private JobDescriptionDetail toDetail(JobDescription jd) {
        return new JobDescriptionDetail(jd.getId(), jd.getTitle(), jd.getCompanyName(),
                jd.getJdText(), jd.getParsedKeywordsJson(),
                jd.getParsedAt(), jd.getParsedVersion(),
                jd.getCreatedAt(), jd.getUpdatedAt());
    }
}

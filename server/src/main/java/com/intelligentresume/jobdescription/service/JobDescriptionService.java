package com.intelligentresume.jobdescription.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.dto.JobDescriptionCreateRequest;
import com.intelligentresume.jobdescription.dto.JobDescriptionResponse;
import com.intelligentresume.jobdescription.parser.JdKeywordParser;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * JD 领域服务。
 *
 * <p>骨架:T05 落地解析覆盖、原文保护、跨用户、软删。
 */
@Service
public class JobDescriptionService {

    private final JobDescriptionRepository repository;
    private final JdKeywordParser parser;

    public JobDescriptionService(JobDescriptionRepository repository, JdKeywordParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    @Transactional
    public JobDescriptionResponse create(JobDescriptionCreateRequest request, Long userId) {
        JobDescription jd = new JobDescription();
        jd.setUserId(userId);
        jd.setTitle(request.title());
        jd.setCompanyName(request.companyName());
        jd.setJdText(request.jdText());
        JobDescription saved = repository.save(jd);
        return toResponse(saved);
    }

    public List<JobDescriptionResponse> list(Long userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::toResponse).toList();
    }

    public JobDescriptionResponse get(Long id, Long userId) {
        return toResponse(loadOwned(id, userId));
    }

    @Transactional
    public JobDescriptionResponse update(Long id, JobDescriptionCreateRequest request, Long userId) {
        JobDescription jd = loadOwned(id, userId);
        if (request.title() != null) jd.setTitle(request.title());
        if (request.companyName() != null) jd.setCompanyName(request.companyName());
        // jd_text 允许覆盖:用户编辑后原文也会更新,parsed_keywords 在下次 parse 时重建。
        if (request.jdText() != null) jd.setJdText(request.jdText());
        return toResponse(repository.save(jd));
    }

    @Transactional
    public JobDescriptionResponse parse(Long id, Long userId) {
        JobDescription jd = loadOwned(id, userId);
        Map<String, Object> parsed = parser.parse(jd.getJdText());
        jd.setParsedKeywordsJson(parsed);
        jd.setParsedAt(LocalDateTime.now());
        jd.setParsedVersion(JdKeywordParser.PARSER_VERSION);
        return toResponse(repository.save(jd));
    }

    @Transactional
    public void softDelete(Long id, Long userId) {
        JobDescription jd = loadOwned(id, userId);
        jd.setDeletedAt(LocalDateTime.now());
        repository.save(jd);
    }

    private JobDescription loadOwned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private JobDescriptionResponse toResponse(JobDescription jd) {
        return new JobDescriptionResponse(jd.getId(), jd.getTitle(), jd.getCompanyName(), jd.getJdText(),
                jd.getParsedKeywordsJson(), jd.getParsedAt(), jd.getParsedVersion(),
                jd.getCreatedAt(), jd.getUpdatedAt());
    }
}
package com.intelligentresume.resume.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.dto.*;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import com.intelligentresume.resume.domain.ResumeSourceType;
import java.util.Map;

/**
 * 简历主档的 CRUD 与"当前版本"指针切换；版本内容本身由 {@link ResumeVersionService} 管理。
 *
 * <p><b>① 解决什么业务问题</b>：用户要有一份"简历档案"作为容器——它本身只有标题等元信息，
 * 真正的内容（工作经历、教育背景等，存成 JSON）放在一条条"版本"里（每次保存生成一个版本，
 * 可回滚）。本类管理这个容器：建、改名、列、删，以及决定"现在生效的是哪个版本"。
 *
 * <p><b>② 在链路哪里</b>：上游是 {@code ResumeController}（简历页面的增删改查请求）；
 * 本类读写 {@link ResumeRepository}（主档表）与 {@link ResumeVersionRepository}（版本表）；
 * 版本的创建/保存/回滚在 {@link ResumeVersionService}，两个类各管一半；
 * 产出 {@code ResumeDetail}/{@code ResumeSummary} 给前端简历列表页。
 *
 * <p><b>③ 改坏它用户会看到什么</b>：查询漏掉 userId 条件 → 用户能看到/改别人的简历（越权）；
 * 删除误删版本行 → 历史快照丢失、无法恢复；切换当前版本不校验归属 → 简历可能指向别人的内容。
 *
 * <p>要点：
 * <ul>
 *     <li>所有查询都带 {@code userId} 条件——跨用户一律 404，不泄露"存在但不属于你"。</li>
 *     <li>删除是软删（软删除 = 不真删，只写 {@code deleted_at} 标记，查询时自动过滤）；
 *         版本不跟着删，历史快照必须保留。</li>
 *     <li>{@code create} 若同时传了 {@code resumeJson}，会顺带落一条 v1 并直接设为当前版本；
 *         不传则简历处于"无当前版本"状态，等前端首次保存版本。</li>
 *     <li>切换当前版本前要校验"版本属于该简历"且"未归档"，否则会指向不可见的内容。</li>
 * </ul>
 */
@Service
public class ResumeService {

    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final JsonResumeValidator jsonResumeValidator;

    public ResumeService(ResumeRepository resumeRepository,
                         ResumeVersionRepository resumeVersionRepository,
                         JsonResumeValidator jsonResumeValidator) {
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.jsonResumeValidator = jsonResumeValidator;
    }

    /**
     * 创建简历主档；{@code resumeJson} 非空时同时落一条 {@code MANUAL} 的 v1 版本并设为当前版本。
     *
     * <p>步骤：① 先建只有标题的主档；② 若带了初始内容 JSON——先校验结构合法，
     * 再落一条 versionNo=1、来源 MANUAL 的版本行，并把主档的当前版本指针指向它。
     *
     * <p>两个写入在同一事务里：版本校验失败（JSON 结构不合法或超限）会连主档一起回滚，
     * 不会留下一条没有内容的空简历——所以②必须和①在同一事务，不能拆。
     */
    @Transactional
    public ResumeDetail create(CreateResumeRequest req, Long userId) {
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle(req.title());
        resumeRepository.save(resume);

        if (req.resumeJson() != null && !req.resumeJson().isEmpty()) {
            jsonResumeValidator.validate(req.resumeJson());
            int versionNo = 1;
            ResumeVersion version = new ResumeVersion();
            version.setResumeId(resume.getId());
            version.setVersionNo(versionNo);
            version.setSourceType(ResumeSourceType.MANUAL);
            version.setResumeJson(req.resumeJson());
            version.setCreatedBy(userId);
            resumeVersionRepository.save(version);
            resume.setCurrentVersionId(version.getId());
            resumeRepository.save(resume);
        }

        return toDetail(resume);
    }

    @Transactional(readOnly = true)
    public List<ResumeSummary> list(Long userId) {
        return resumeRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * 查询某 JD 关联的岗位简历。用于"同 JD 再次生成"时判断新建/更新。
     */
    @Transactional(readOnly = true)
    public List<ResumeSummary> listByJobDescription(Long jdId, Long userId) {
        return resumeRepository.findByUserIdAndJobDescriptionIdAndDeletedAtIsNull(userId, jdId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ResumeDetail get(Long id, Long userId) {
        Resume resume = findOwned(id, userId);
        return toDetail(resume);
    }

    @Transactional
    public ResumeDetail update(Long id, UpdateResumeRequest req, Long userId) {
        Resume resume = findOwned(id, userId);
        if (req.title() != null && !req.title().isBlank()) {
            resume.setTitle(req.title());
        }
        resumeRepository.save(resume);
        return toDetail(resume);
    }

    /**
     * 软删除简历：只写 {@code deleted_at}，{@code @SQLRestriction} 会让它从所有查询里消失。
     *
     * <p>版本行不动——物理删除由留存清扫按引用判定处理，这里删掉就无法恢复。
     */
    @Transactional
    public void softDelete(Long id, Long userId) {
        Resume resume = findOwned(id, userId);
        resume.setDeletedAt(LocalDateTime.now());
        resumeRepository.save(resume);
    }

    /**
     * 切换简历的当前版本指针（用户在版本列表里点"设为当前"）。
     *
     * <p>步骤：① 加行锁读主档并确认归属（防止两个请求同时切换造成指针错乱）；
     * ② 校验目标版本存在，且它的 resumeId 与路径上的一致——防"拿别人的版本 id 挂到自己的简历上"；
     * ③ 校验版本未归档（归档版本不可见，指向它会让简历打开后是空的）；
     * ④ 更新指针保存。三道校验缺一不可；校验顺序先归属后状态，避免通过错误码差异探测他人简历。
     */
    @Transactional
    public void setCurrentVersion(Long resumeId, Long versionId, Long userId) {
        Resume resume = findOwnedForUpdate(resumeId, userId);
        ResumeVersion version = resumeVersionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本不存在"));
        if (!version.getResumeId().equals(resumeId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本不属于该简历");
        }
        if (version.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "归档版本不能设为当前版本");
        }
        resume.setCurrentVersionId(versionId);
        resumeRepository.save(resume);
    }

    // ---- helpers ----

    private Resume findOwned(Long id, Long userId) {
        return resumeRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
    }

    private Resume findOwnedForUpdate(Long id, Long userId) {
        // The fallback keeps Mockito/unit-test doubles and older repository adapters
        // compatible; the Spring Data query is the path used by the application.
        return resumeRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseGet(() -> findOwned(id, userId));
    }

    private ResumeSummary toSummary(Resume r) {
        return new ResumeSummary(r.getId(), r.getTitle(), r.getCurrentVersionId(),
                r.getJobDescriptionId(), r.getUpdatedAt());
    }

    private ResumeDetail toDetail(Resume r) {
        return new ResumeDetail(r.getId(), r.getTitle(), r.getCurrentVersionId(), r.getJobDescriptionId(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}

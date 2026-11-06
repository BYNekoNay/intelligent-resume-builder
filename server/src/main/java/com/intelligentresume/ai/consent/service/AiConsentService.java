package com.intelligentresume.ai.consent.service;

import com.intelligentresume.ai.consent.domain.AiConsent;
import com.intelligentresume.ai.consent.domain.ConsentStatus;
import com.intelligentresume.ai.consent.dto.ConsentResponse;
import com.intelligentresume.ai.consent.dto.GrantConsentRequest;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/**
 * AI 同意服务 —— "用户是否授权把资料发给 AI"的唯一裁判。事件溯源模型:每次操作追加新事件,不修改历史。
 *
 * <p>【业务问题】用户简历属于敏感个人信息，发给模型前必须有显式授权；用户也有权随时撤回。
 * 还要能举证"用户何时同意过哪个版本的协议"——所以不能只存一个开关位，要存每次操作的流水。</p>
 *
 * <p>【链路位置】前端经 {@code AiConsentController} 调 {@link #grant}/{@link #withdraw}；
 * {@code AiTaskService}（创建任务前）与 {@code TaskExecutionService}（执行前）调
 * {@link #hasValidConsent} 做校验；账号删除流程调 {@link #withdrawIfGranted}。</p>
 *
 * <p>【改坏它会怎样】历史事件被 UPDATE/删除会让授权失去举证能力；
 * 细粒度校验漏掉 policyVersion 比对会让旧协议授权"永久有效"，绕过新协议的重新同意。</p>
 *
 * <p>判定规则很朴素:**最新一条事件**说了算。最新是 GRANTED 才算已授权,撤回同样只是追加一条。</p>
 *
 * <p>要点 / 红线:</p>
 * <ul>
 *   <li>表是 append-only:不要 UPDATE 或删除历史行,否则"用户何时同意过什么"就无法举证。</li>
 *   <li>细粒度校验({@code hasValidConsent(userId, taskScope, dataCategories)})额外要求
 *       policyVersion 与当前配置一致 —— **改 {@code app.ai.consent.policy-version} 后,
 *       所有存量授权都过不了这项校验**,用户必须重新同意。这是合规要求,不是 bug。</li>
 *   <li>范围匹配是列表精确包含:{@code taskScopes} 必须原样含有任务类型名,
 *       {@code dataCategories} 必须包含本次要求的全部类别(用 {@code containsAll})。</li>
 *   <li>撤回时沿用最新事件的 policyVersion/providerCode 等字段,不要从请求里取,避免伪造。</li>
 *   <li>当前只接受 {@code bailian} 一个 providerCode,其它值一律 VALIDATION。</li>
 * </ul>
 */
@Service
public class AiConsentService {

    private static final String PROVIDER_CODE = "bailian";

    private final AiConsentRepository repository;

    @Value("${app.ai.consent.policy-version:v1.2.0}")
    private String policyVersion;

    public AiConsentService(AiConsentRepository repository) {
        this.repository = repository;
    }

    /**
     * 授权 AI 数据处理。校验 policyVersion 后追加 GRANTED 事件。
     */
    @Transactional
    public ConsentResponse grant(GrantConsentRequest req, Long userId) {
        if (!policyVersion.equals(req.policyVersion())) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "隐私政策版本不匹配,当前版本: " + policyVersion);
        }
        if (!PROVIDER_CODE.equals(req.providerCode())) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "当前仅支持 AI 提供者: " + PROVIDER_CODE);
        }

        AiConsent consent = new AiConsent();
        consent.setUserId(userId);
        consent.setEventType(ConsentStatus.GRANTED);
        consent.setPolicyVersion(req.policyVersion());
        consent.setProviderCode(req.providerCode());
        consent.setTaskScopesJson(req.taskScopes());
        consent.setDataCategoriesJson(req.dataCategories());
        consent.setNoticeHash(req.noticeHash());

        consent = repository.save(consent);
        return toResponse(consent);
    }

    /**
     * 获取用户最新的同意事件。无事件时返回 null。
     */
    @Transactional(readOnly = true)
    public ConsentResponse current(Long userId) {
        return repository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * 撤回同意。追加 WITHDRAWN 事件,不修改历史。
     */
    @Transactional
    public ConsentResponse withdraw(Long userId) {
        // 获取最新事件以继承 policyVersion 和 providerCode
        AiConsent latest = repository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "尚无同意记录,无法撤回"));

        return toResponse(appendWithdrawal(latest));
    }

    /**
     * 账号删除时撤回现有授权；没有授权或已经撤回时保持幂等。
     */
    @Transactional
    public boolean withdrawIfGranted(Long userId) {
        return repository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .filter(c -> c.getEventType() == ConsentStatus.GRANTED)
                .map(latest -> {
                    appendWithdrawal(latest);
                    return true;
                })
                .orElse(false);
    }

    private AiConsent appendWithdrawal(AiConsent latest) {
        AiConsent consent = new AiConsent();
        consent.setUserId(latest.getUserId());
        consent.setEventType(ConsentStatus.WITHDRAWN);
        consent.setPolicyVersion(latest.getPolicyVersion());
        consent.setProviderCode(latest.getProviderCode());
        consent.setTaskScopesJson(latest.getTaskScopesJson());
        consent.setDataCategoriesJson(latest.getDataCategoriesJson());
        consent.setNoticeHash(latest.getNoticeHash());

        consent = repository.save(consent);
        return consent;
    }

    /**
     * 检查用户是否有有效的 AI 同意(最新事件为 GRANTED)。
     * 供 AiTaskService 在创建任务前调用。
     */
    @Transactional(readOnly = true)
    public boolean hasValidConsent(Long userId) {
        return repository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .map(c -> c.getEventType() == ConsentStatus.GRANTED)
                .orElse(false);
    }

    /**
     * 细粒度校验:最新事件必须是 GRANTED、政策版本与当前配置一致、
     * 且授权范围覆盖本次任务类型与全部数据类别。
     *
     * <p>步骤:①取最新事件 ②依次过滤四条件（已授权 → 版本一致 → 范围含任务类型 → 类别全覆盖），
     * 任一条不满足都返回 false(不区分原因),调用方统一报 CONSENT_REQUIRED —— 不区分原因
     * 是刻意的:不向未授权用户泄露"差哪一条"。</p>
     */
    @Transactional(readOnly = true)
    public boolean hasValidConsent(Long userId, String taskScope, Collection<String> dataCategories) {
        return repository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId)
                .filter(c -> c.getEventType() == ConsentStatus.GRANTED)
                .filter(c -> policyVersion.equals(c.getPolicyVersion()))
                .filter(c -> c.getTaskScopesJson() != null && c.getTaskScopesJson().contains(taskScope))
                .filter(c -> c.getDataCategoriesJson() != null
                        && c.getDataCategoriesJson().containsAll(dataCategories))
                .isPresent();
    }

    private ConsentResponse toResponse(AiConsent c) {
        return new ConsentResponse(
                c.getId(),
                c.getPolicyVersion(),
                c.getProviderCode(),
                c.getTaskScopesJson(),
                c.getDataCategoriesJson(),
                c.getEventType(),
                c.getCreatedAt()
        );
    }
}

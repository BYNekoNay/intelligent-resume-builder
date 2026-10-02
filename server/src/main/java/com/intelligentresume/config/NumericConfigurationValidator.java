package com.intelligentresume.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数值型运维配置的取值校验（fail-closed，所有 profile 生效）。
 *
 * <p>背景（第三十七批取证）：这些键全部可由环境变量注入，而 {@code @Value} 只保证
 * 「能解析成整数」——取 0 或负数会被**静默接受**，运行时才表现为难查的故障。已实证的四种：
 * <ul>
 *   <li>{@code AI_TASK_CLEANUP_BATCH_SIZE=0} → {@code PageRequest.of(0, 0)} 抛
 *       {@code IllegalArgumentException: Page size must not be less than one}，保留期清理
 *       每次运行都失败（间隔 24h ⇒ 一天一条错误日志）；</li>
 *   <li>{@code AI_TASK_CLEANUP_MAX_ROWS_PER_RUN=0} → 续批条件 {@code total < 0} 不成立，
 *       **静默不清理**：3 行合格超期行一轮返回 0，超期快照继续累积（与 #26 的目标相反）；</li>
 *   <li>{@code AI_TASK_RETENTION_DAYS=0} → cutoff = now，**保留期语义反转**：刚创建的终态任务
 *       立刻被压缩（实测 1 行新建任务被清空结果）；</li>
 *   <li>{@code RATE_LIMIT_LOGIN=0} → 登录端点**永久 429**：应用启动正常、`/api/system/health`
 *       200，但真实 HTTP 探针下第 1 次登录即 {@code 429 + 42901}（无人能登录）。</li>
 * </ul>
 * 故把「必须为正」的键集中为一张表，在启动时校验、非法即拒绝（与
 * {@code ProductionConfigurationValidator}、pdf-service 的环境变量校验同一口径）。
 *
 * <p>表驱动而非逐个 {@code @Value} 参数：新增键只需加一行，且测试可据同一张表覆盖每个键。
 */
@Component
public class NumericConfigurationValidator {

    /** 键 → 允许的最小值（含）。 */
    private static final Map<String, Integer> MINIMUMS = buildMinimums();

    private final Environment environment;

    public NumericConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        Map<String, Integer> missing = new TreeMap<>();
        Map<String, String> violations = new TreeMap<>();
        MINIMUMS.forEach((key, minimum) -> {
            Integer value = environment.getProperty(key, Integer.class);
            if (value == null) {
                // 键缺省时无法判定：整套键都在 application.yml 声明，缺任何一个都说明解析路径失效
                missing.put(key, minimum);
            } else if (value < minimum) {
                violations.put(key, value + " < " + minimum);
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("数值型配置未解析，本校验失效（防静默空转）：" + missing.keySet()
                    + "；请确认 application.yml 仍声明这些键");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("数值型运维配置取值非法（0/负值会被静默接受，表现为功能静默失效、"
                    + "作业每次失败或端点永久 429）：" + violations);
        }
    }

    /** 受校验的键与最小值（供测试覆盖每个键）。 */
    static Map<String, Integer> minimums() {
        return MINIMUMS;
    }

    private static Map<String, Integer> buildMinimums() {
        Map<String, Integer> minimums = new LinkedHashMap<>();
        // 限流阈值：0/负值会让对应端点永久 429（登录/注册/刷新/解析/凭证变更/导出全部不可用）
        for (String endpoint : new String[]{"login", "register", "refresh", "resume-import-parse",
                "jd-parse", "change-credential", "account-export"}) {
            minimums.put("app.security.rate-limit." + endpoint + "-per-minute", 1);
        }
        // 分桶硬上限（#43）：0 会让所有受限端点直接 fail-closed
        minimums.put("app.security.rate-limit.max-buckets", 1);
        // AI 任务留存：0 天反转保留期语义、批量 0 让作业每次抛异常、单轮上限 0 静默不清理
        minimums.put("app.ai.task.retention-days", 1);
        minimums.put("app.ai.task.cleanup-batch-size", 1);
        minimums.put("app.ai.task.cleanup-max-rows-per-run", 1);
        // 周期作业间隔：0 会让 @Scheduled(fixedDelay=0) 变成连轴转的热循环
        minimums.put("app.ai.task.cleanup-interval-ms", 1000);
        // PDF 导出过期清理：与 AI 留存同形（PageRequest 批量 + fixedDelay 间隔）
        minimums.put("app.pdf.cleanup-batch-size", 1);
        minimums.put("app.pdf.cleanup-interval-ms", 1000);
        // 数据生命周期分档清扫（决策 D2，第 64 批）：批量 0 → 一个候选都取不回来（守卫生效但清扫静默停摆）；
        // 周期 0 → 连轴转的热循环。恢复期/宽限期下限为 0（合法但激进）：取负值会让 cutoff 落到未来、
        // 把**尚未超过保留期**的软删行提前纳入删除候选 —— 保留期语义反转。
        minimums.put("app.retention.purge.batch-size", 1);
        minimums.put("app.retention.purge.recovery-days", 0);
        minimums.put("app.retention.purge.grace-days", 0);
        minimums.put("app.retention.purge.interval-ms", 1000);
        // 账户清扫（决策 D2 阶段 3，第 74 批）：批量 0 → 到期任务永远清不掉（承诺落空）；
        // 间隔 0 → 热循环；撤销窗口天数 0 → 删号即失去撤销机会（合法下限为 1，承诺口径 7 天）。
        minimums.put("app.retention.account-purge.batch-size", 1);
        minimums.put("app.retention.account-purge.interval-ms", 1000);
        minimums.put("app.retention.account-deletion.grace-days", 1);
        return Collections.unmodifiableMap(minimums);
    }
}

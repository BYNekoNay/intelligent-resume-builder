package com.intelligentresume.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流阈值配置（`app.security.rate-limit.*`）。
 *
 * <p>为什么从一堆 `@Value` 收成一个类：阈值此前散落在 {@code RateLimitFilter} 的 10 个构造参数里，
 * 每加一个受限端点就要改构造器签名、所有测试、所有 profile。现在阈值只在这里声明一次，
 * 「哪些路径受哪档限流」由 {@link RateLimitRegistry} 单独表达（路径与阈值分开维护，各自的变更理由不同）。
 *
 * <p>这里的默认值必须与 {@code application.yml} 一致：yml 的键写成
 * {@code ${RATE_LIMIT_XXX:默认值}}，两处默认值不一致会让「yml 缺键」变成静默改阈值。
 * {@code NumericConfigurationValidator} 负责兜住 0/负值（0 会让端点永久 429）。
 *
 * <p>阈值怎么定的（都是「单 IP、每分钟」）：
 * <ul>
 *   <li><b>口令验证面</b>（login/register/refresh/凭证变更/账号导出）——最严，防暴力破解与数据放大器；</li>
 *   <li><b>资源放大器</b>（简历解析/PDF 渲染/ATS 体检）——单次请求吃 CPU 或 Chromium，按分钟卡住即可；</li>
 *   <li><b>AI 与面试</b>——真正的成本上限是每人每日配额（{@code AiQuotaService}），
 *       这里只是突发闸：防止一个人的 30 次/日配额在一分钟内打满；</li>
 *   <li><b>其余写操作</b>——走 {@code write-default} 兜底。**兜底的意义是「没有无上限的写端点」**，
 *       阈值给得宽松（默认 240/min ≈ 4/s），只为拦住脚本级的误用与放大攻击。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.security.rate-limit")
public class RateLimitProperties {

    /** 登录（与删除撤销共享同一口令验证桶）。 */
    private int loginPerMinute = 10;

    /** 注册。 */
    private int registerPerMinute = 5;

    /** 刷新令牌。 */
    private int refreshPerMinute = 30;

    /** 改密/改邮箱（共享桶：在两端点间交替不该获得双倍预算）。 */
    private int changeCredentialPerMinute = 5;

    /** 账号导出（单次请求重读并序列化整个账号）。 */
    private int accountExportPerMinute = 3;

    /** 简历导入解析（PDFBox/POI 全内存解析）。 */
    private int resumeImportParsePerMinute = 6;

    /** JD 文本解析（本地轻量，但按 JD id 分桶等于放大阈值，故按规则组共享）。 */
    private int jdParsePerMinute = 15;

    /** PDF 渲染与渲染任务重试（每次都占一个 Chromium 上下文）。 */
    private int pdfRenderPerMinute = 10;

    /** AI 生成与预览（含岗位选材、内联优化、沟通稿）。 */
    private int aiGeneratePerMinute = 20;

    /** ATS 体检（规则打分 + 文本重排 + 可能启动 AI 任务）。 */
    private int atsCheckPerMinute = 10;

    /** 面试回合（开始/作答/AI 重评），单次都会触发评估。 */
    private int interviewTurnPerMinute = 20;

    /** 桌面端写操作（导入落库、备份恢复），低频且重。 */
    private int desktopMutationPerMinute = 5;

    /** 系统健康详情：会触发出站探测（PDF / 模型 PROVIDER）。 */
    private int healthDetailPerMinute = 10;

    /** 所有未被上面命中的写操作的兜底阈值（保证「没有无上限的写端点」）。 */
    private int writeDefaultPerMinute = 240;

    /** 分桶 map 硬上限：达到后新分桶 fail-closed（防海量唯一 IP 顶爆内存）。 */
    private int maxBuckets = 10000;

    public int getLoginPerMinute() { return loginPerMinute; }
    public void setLoginPerMinute(int loginPerMinute) { this.loginPerMinute = loginPerMinute; }

    public int getRegisterPerMinute() { return registerPerMinute; }
    public void setRegisterPerMinute(int registerPerMinute) { this.registerPerMinute = registerPerMinute; }

    public int getRefreshPerMinute() { return refreshPerMinute; }
    public void setRefreshPerMinute(int refreshPerMinute) { this.refreshPerMinute = refreshPerMinute; }

    public int getChangeCredentialPerMinute() { return changeCredentialPerMinute; }
    public void setChangeCredentialPerMinute(int changeCredentialPerMinute) { this.changeCredentialPerMinute = changeCredentialPerMinute; }

    public int getAccountExportPerMinute() { return accountExportPerMinute; }
    public void setAccountExportPerMinute(int accountExportPerMinute) { this.accountExportPerMinute = accountExportPerMinute; }

    public int getResumeImportParsePerMinute() { return resumeImportParsePerMinute; }
    public void setResumeImportParsePerMinute(int resumeImportParsePerMinute) { this.resumeImportParsePerMinute = resumeImportParsePerMinute; }

    public int getJdParsePerMinute() { return jdParsePerMinute; }
    public void setJdParsePerMinute(int jdParsePerMinute) { this.jdParsePerMinute = jdParsePerMinute; }

    public int getPdfRenderPerMinute() { return pdfRenderPerMinute; }
    public void setPdfRenderPerMinute(int pdfRenderPerMinute) { this.pdfRenderPerMinute = pdfRenderPerMinute; }

    public int getAiGeneratePerMinute() { return aiGeneratePerMinute; }
    public void setAiGeneratePerMinute(int aiGeneratePerMinute) { this.aiGeneratePerMinute = aiGeneratePerMinute; }

    public int getAtsCheckPerMinute() { return atsCheckPerMinute; }
    public void setAtsCheckPerMinute(int atsCheckPerMinute) { this.atsCheckPerMinute = atsCheckPerMinute; }

    public int getInterviewTurnPerMinute() { return interviewTurnPerMinute; }
    public void setInterviewTurnPerMinute(int interviewTurnPerMinute) { this.interviewTurnPerMinute = interviewTurnPerMinute; }

    public int getDesktopMutationPerMinute() { return desktopMutationPerMinute; }
    public void setDesktopMutationPerMinute(int desktopMutationPerMinute) { this.desktopMutationPerMinute = desktopMutationPerMinute; }

    public int getHealthDetailPerMinute() { return healthDetailPerMinute; }
    public void setHealthDetailPerMinute(int healthDetailPerMinute) { this.healthDetailPerMinute = healthDetailPerMinute; }

    public int getWriteDefaultPerMinute() { return writeDefaultPerMinute; }
    public void setWriteDefaultPerMinute(int writeDefaultPerMinute) { this.writeDefaultPerMinute = writeDefaultPerMinute; }

    public int getMaxBuckets() { return maxBuckets; }
    public void setMaxBuckets(int maxBuckets) { this.maxBuckets = maxBuckets; }
}

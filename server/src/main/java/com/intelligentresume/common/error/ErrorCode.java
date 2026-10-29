package com.intelligentresume.common.error;

public enum ErrorCode {
    VALIDATION(40001, "参数错误"),
    UNAUTHENTICATED(40101, "未登录或 Token 无效"),
    FORBIDDEN(40301, "无权限访问"),
    CONSENT_REQUIRED(40302, "AI 数据处理未授权或已撤回"),
    NOT_FOUND(40401, "资源不存在"),
    RATE_LIMITED(42901, "请求频率或 AI 配额超限"),
    CONFLICT(40901, "资源版本冲突或非法状态迁移"),
    /**
     * 归档版本被下游能力消费（ATS/导出/投递/沟通/评分/面试）。
     *
     * <p>与 {@link #CONFLICT} 区分：归档是**可逆**状态，用户可据此「先恢复再重试」；
     * 而状态机冲突/乐观锁的处置是「刷新」或「换一个版本」。前端按业务码映射文案
     * （不透传服务端 message），若无专属码，用户只会看到泛化的「内容已更新…请刷新后重试」，
     * 与真实处置动作不符。
     */
    VERSION_ARCHIVED(40902, "简历版本已归档"),
    ACCOUNT_DELETION_PENDING(40303, "账号处于删除撤销期"),
    ACCOUNT_DELETION_WINDOW_EXPIRED(40903, "删除撤销期已结束"),
    /**
     * 确认草稿时，selected 里出现了 items 完全没有覆盖的素材路径。
     *
     * <p>与 {@link #CONFLICT} 区分：40901 的处置是「刷新重试」，而本码的处置是
     * 「重新载入选材」——客户端持有的选择集已经过期（少提交了某些项），重试同一请求必然再失败。
     * 历史语义是静默 continue（ADR-011 Decision 3），实测后果是「版本已落地但少了溯源引用」
     * 且无任何信号；v0.5.0 起改为显式拒绝（ADR-013）。
     *
     * <p>注意码值：登记册（OPEN-DECISIONS 2026-10-28）原写 40903，但 40903 已被
     * {@link #ACCOUNT_DELETION_WINDOW_EXPIRED} 占用；沿用会让前端文案映射撞车，故取 40904。
     */
    SELECTION_ITEM_UNKNOWN(40904, "选材已过期，请重新载入后确认"),
    INTERNAL(50001, "系统异常"),
    AI_FAILURE(50002, "AI 调用失败"),
    PDF_FAILURE(50003, "PDF 导出失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}

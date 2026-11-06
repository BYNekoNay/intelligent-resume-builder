package com.intelligentresume.system;

import java.util.List;

/**
 * 健康契约里对外公布的能力码（稳定、有序）。
 *
 * <p>【小白导览】一张「本平台有哪些功能模块」的固定名单（简历、AI 任务、ATS、面试……），
 * 出现在健康明细接口里给运维对号入座。改坏它（改名/删除/调序）→ 前端与快照测试对不上。
 *
 * <p>这些码会被前端/运维按字符串判断能力是否可用：只能新增，不能改名或删除；
 * 顺序即响应中的顺序，调整顺序会改变快照类测试与前端展示。只有认证端点
 * {@code /api/system/health/detail} 会返回它，匿名探针不暴露。
 */
public final class SystemCapabilityRegistry {

    private SystemCapabilityRegistry() {
    }

    /** 能力码清单（不可变、有序）。 */
    public static List<String> codes() {
        return List.of("resume", "ai-tasks", "ats", "applications", "communications",
                "interviews", "imports", "pdf-export");
    }
}

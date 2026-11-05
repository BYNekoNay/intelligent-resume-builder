package com.intelligentresume.system;

import java.util.List;

/**
 * 健康契约里对外公布的能力码（稳定、有序）。
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

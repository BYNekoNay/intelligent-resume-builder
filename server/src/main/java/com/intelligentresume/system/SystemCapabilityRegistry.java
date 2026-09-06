package com.intelligentresume.system;

import java.util.List;

/** Stable API capability codes advertised by the application health contract. */
public final class SystemCapabilityRegistry {

    private SystemCapabilityRegistry() {
    }

    public static List<String> codes() {
        return List.of("resume", "ai-tasks", "ats", "applications", "communications",
                "interviews", "imports", "pdf-export");
    }
}

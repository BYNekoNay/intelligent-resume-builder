package com.intelligentresume.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 桌面本地工作区的开关与路径（ADR-012 D1-D3）。
 *
 * <p>默认全部关闭：在线版与 CI 不受任何影响，只有 {@code --spring.profiles.active=desktop}
 * 时 {@code enabled} 才为 true。
 */
@ConfigurationProperties(prefix = "app.desktop")
public class DesktopModeProperties {

    /** 是否启用桌面本地工作区模式（免登录 + 本地文件库）。 */
    private boolean enabled = false;

    /** 本机数据目录：H2 文件、PDF 输出、后续备份快照都放这里。 */
    private String dataDir = "./desktop-data";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}

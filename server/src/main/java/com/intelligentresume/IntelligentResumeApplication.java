package com.intelligentresume;

import com.intelligentresume.config.DesktopModeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DesktopModeProperties.class)
public class IntelligentResumeApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(IntelligentResumeApplication.class, args);
        } catch (Exception startupFailure) {
            // 启动失败必须以非零码退出，而不是留一个「窗口没反应」的僵尸进程：
            // 上下文启动失败时，H2 的 MVStore、Hikari 等非守护线程仍会吊住 JVM（实测进程不退出）。
            // 桌面端是单进程应用，退出码是启动器唯一能拿到的信号。
            System.err.println("启动失败：" + startupFailure.getMessage());
            System.exit(1);
        }
    }
}

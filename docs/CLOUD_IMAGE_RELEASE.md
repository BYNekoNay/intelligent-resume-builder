# 云端镜像发布（迁移入口）

镜像发布与 ECS 部署已统一到[部署运行手册](./DEPLOYMENT.md)。

当前唯一支持的链路是 GitHub Actions 构建 Linux/amd64 镜像，推送至阿里云 ACR，再由 ECS 拉取运行。旧的 Gitee 流水线说明、杭州 Registry 示例和直接在服务器构建的做法均不再适用。

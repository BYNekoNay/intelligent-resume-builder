# GitHub CI/CD 流水线

本文说明仓库当前的 GitHub Actions 校验与镜像发布流程。流水线不保存业务密钥，不执行自动投递，也不直接部署或修改 ECS。

## 持续集成

工作流文件：`.github/workflows/ci.yml`。

触发条件：

- 向 `master` 推送提交；
- 以 `master` 为目标分支创建或更新 Pull Request；
- 在 GitHub Actions 页面手动运行。

流水线使用只读仓库权限，并发运行三个独立 Job：

| Job | 环境 | 命令与覆盖范围 |
| --- | --- | --- |
| `Server tests` | Temurin Java 17、Maven 缓存 | `mvn test`；Surefire 配置会执行 `*Test.java` 和 `*IT.java`，测试 profile 使用 H2/MySQL 兼容模式并关闭自动 worker 调度 |
| `Web build and browser tests` | Node.js 20、Chromium | `npm ci`、`npm run build`、`npm run test:e2e`；覆盖 i18n 守卫、TypeScript、生产构建和 mock API 浏览器流程 |
| `PDF service checks` | Node.js 20 | `npm ci`、`npm run check`、`npm test`；覆盖 Node 语法、模板解析和 PDF 服务单测 |

PR 合并前应将三个 Job 都配置为必需检查。路径：`Settings -> Branches -> Branch protection rules -> master`，启用 `Require status checks to pass before merging`，选择上述三个检查。

CI 不运行真实百炼调用、本地 MySQL 三服务联调、ACR 推送或 ECS 部署。这些能力分别由本地验证脚本和手动发布工作流负责，避免在不可信 PR 中暴露 Secret 或消耗外部额度。

## 镜像发布

工作流文件：`.github/workflows/publish-acr-images.yml`。该工作流只接受手动 `workflow_dispatch`，输入不可变的 `image_tag` 后构建并推送 API、Web、PDF、Edge 和 MySQL 五个 Linux/amd64 镜像。

GitHub 仓库需要配置以下 Actions Secrets：

- `ACR_REGISTRY`
- `ACR_NAMESPACE`
- `ACR_USERNAME`
- `ACR_PASSWORD`

发布前先确认目标提交的 CI 全部通过。镜像工作流不自动更新 ECS，部署、验证与回滚仍按 [部署运行手册](./DEPLOYMENT.md) 执行。

## 故障排查

- `npm ci` 失败：检查对应目录的 `package.json` 与 `package-lock.json` 是否同步。
- Playwright 安装或启动失败：确认 `Install Chromium` 步骤成功，查看系统依赖安装日志。
- 后端 IT 失败：先确认测试使用 `application-test.yml`，没有注入生产数据库或真实 AI Key。
- 发布工作流缺少变量：在仓库 Actions Secrets 中补齐缺失项，不要把值写入 workflow、日志或提交。
- 工作流没有触发：确认 PR 的目标分支或推送分支是 `master`，并检查仓库是否启用 GitHub Actions。

## 当前边界

GitHub Actions 是 GitHub 仓库的权威 CI/CD 执行面。Gitee 用于代码镜像和协作备份，不配置重复的云端流水线，以免同一提交重复消耗资源或产生发布竞态。

# 不可变发布候选与原子切换

本手册只定义应用文件的候选构建、校验、切换和回滚。它不导入、删除、恢复或连接任何业务数据库；数据库迁移与恢复演练必须在独立的隔离 MySQL 环境中另行完成。

## 发布前条件

1. 当前提交已评审，工作区干净，CI 的 Source hygiene、后端、前端和 MySQL Flyway 门禁均为绿灯。
2. `scripts/verify-release-source.ps1` 和 `.github/scripts/verify-release-source.sh` 均已通过。两者会拦截被跟踪的业务备份、数据库快照、导出数据和密钥，并只输出路径和审批清单；不会删除文件、解除跟踪或改写历史。
3. 生产服务器已由平台管理员预先配置独立的非 root 发布账号，例如 `deploy`。该账号只能写入发布根目录、执行已批准的用户级服务重启，并通过 SSH 密钥认证；密码、数据库口令和 API Key 不出现在命令行或候选目录中。
4. Nginx（或等价静态服务）指向 `<RemoteRoot>/current/frontend`，后端用户级服务指向 `<RemoteRoot>/current/backend/store-profit-backend.jar`。已有普通目录不能被脚本直接覆盖，必须先由平台管理员完成一次性布局迁移。
5. 当源码门禁报告业务备份或密钥时，先获得数据负责人、仓库负责人和安全负责人的隔离、解除跟踪、历史净化、凭据轮换审批；不要在开发工作区擅自执行 `git rm`、`filter-repo` 或任何历史重写。

当前工作区仍会因 `docs/门店数据备份.json` 被门禁阻断。这是预期的 P0 保护，未完成审批前不得构建或发布候选。

## 冻结候选

在源码目录之外保存候选产物，例如：

```powershell
.\scripts\build-release-candidate.ps1 `
  -CandidateRoot "$env:LOCALAPPDATA\AI-Profit-OS\release-candidates"
```

脚本拒绝脏工作区，并通过 `git archive <commit>` 导出冻结提交，而不是复制当前工作目录。这样不会把被忽略的 `.env`、运行中的 `target`、本地数据库或临时输出混入候选。

候选目录包含：

- `store-profit-backend.jar`
- `frontend-dist.tar.gz`
- `release-manifest.json`

清单记录 Git commit、MySQL/H2 Flyway V54、UTC 构建时间、源码归档 SHA-256，以及两个发布产物的 SHA-256 与字节数。候选目录一旦创建不允许被同名覆盖。

## 校验后部署

先做本地无连接预检：

```powershell
.\scripts\deploy-release-candidate.ps1 `
  -Server release.example.invalid `
  -CandidateDirectory 'C:\release-candidates\release-<id>' `
  -User deploy `
  -RemoteRoot /opt/store-profit `
  -DryRun
```

正式部署必须使用预配的非 root 账号：

```powershell
.\scripts\deploy-release-candidate.ps1 `
  -Server <server> `
  -CandidateDirectory 'C:\release-candidates\release-<id>' `
  -User deploy `
  -RemoteRoot /opt/store-profit `
  -RestartUserService `
  -UserServiceName ai-profit-backend.service `
  -HealthUrl http://127.0.0.1:18081/api/health
```

远端脚本会先校验登录用户不是 root、当前链接位于批准的版本目录下，再上传后端 JAR、前端压缩包和清单。服务器使用 SHA-256 复核三者，在新的 staging 目录完整解包后才将其改名为：

```text
<RemoteRoot>/releases/<candidateId>/
  backend/store-profit-backend.jar
  frontend/index.html
  release-manifest.json
```

最后通过同一文件系统中的 `mv -T` 原子替换 `<RemoteRoot>/current` 符号链接，并记录上一个版本。脚本不会覆盖旧版本、不会使用 root、不会操作数据库，也不会输出凭据。

`-RestartUserService` 仅适用于已由平台管理员配置好的 `systemctl --user` 服务。若重启或健康检查失败，脚本会明确失败并保留候选与上一个链接记录，值班人员应先查看服务日志，再执行经过审批的回滚。

## 回滚

默认切回记录的上一版：

```powershell
.\scripts\rollback-release-candidate.ps1 `
  -Server <server> `
  -User deploy `
  -RemoteRoot /opt/store-profit `
  -RestartUserService `
  -UserServiceName ai-profit-backend.service `
  -HealthUrl http://127.0.0.1:18081/api/health
```

列出可用候选：

```powershell
.\scripts\rollback-release-candidate.ps1 -Server <server> -User deploy -RemoteRoot /opt/store-profit -List
```

回滚到指定已审核版本：

```powershell
.\scripts\rollback-release-candidate.ps1 `
  -Server <server> `
  -User deploy `
  -RemoteRoot /opt/store-profit `
  -ReleaseId <approved-release-id>
```

回滚只会原子切换完整的版本目录（JAR、前端 `index.html`、清单均存在），不会将 MySQL 数据倒回旧版本。若新版本已经产生业务写入，数据库处置必须走独立的数据核对和恢复审批流程。

## 验收记录

每次候选发布至少保存：候选清单、服务器侧 SHA-256 校验结果、切换前后链接目标、服务健康检查、桌面与真实手机验收证据、隔离 MySQL V53→V54 迁移与恢复演练记录，以及灰度观察和回滚决定。静态检查、浏览器尺寸模拟或 `-DryRun` 不能替代真实设备和真实隔离数据库验收。

# AI Profit OS 隔离 QA 运行手册

## 适用范围

本手册仅适用于已审核的候选提交和隔离 QA 环境。它禁止以 3307、生产库、生产 Redis、root 用户或真实生产账号执行任何命令。脚本默认只生成计划和收据，不读取密码、不连接数据库、不启动服务。

## 前置条件

- 已审核的 Git 提交 SHA，独立 worktree 为空；
- Node 20 LTS、JDK、MySQL 客户端和 `mysqldump`；
- loopback QA MySQL（建议 3308）与非 root QA 用户；库名为 `ai_profit_qa_*` 或 `ai_profit_test_*`；
- 独立 QA Redis、真实 HTTPS QA API URL、经审批的脱敏 QA 基线（含一个受控 `E2E_` 品牌和可登录的 QA BOSS）；
- 六个受控账号的密码仅置于当前 Windows 用户或进程环境变量，绝不写入文件、命令行、前端包或证据；
- 任何重置、启动、签名或发布操作的发布负责人明确授权。

## 1. 固定候选来源

```powershell
$candidate = '<reviewed-commit-sha>'
$worktree = "$env:LOCALAPPDATA\AI-Profit-OS\rc-worktrees\rc-$($candidate.Substring(0, 12))"
git worktree add --detach $worktree $candidate
git -C $worktree status --porcelain
```

输出必须为空。若为空以外，停止；不得用 reset 或 clean 修复。

## 2. Node 20 候选构建

```powershell
$node20 = 'C:\Users\34706\AppData\Local\AI-Profit-OS\toolchains\node20\20.20.2'
$env:Path = "$node20;$env:Path"
node --version
npm --version

Push-Location "$worktree\frontend-vue"
npm ci --no-audit --fund=false
npm run build
Pop-Location
```

若 `npm ci` 报锁文件不同步，完整错误写入 `output/release-evidence/<batch>/build/`。先审核 `package.json` 的直接依赖变动，再以审核后的清单同步 `package-lock.json`；禁止 `npm audit fix --force`。之后重新执行 `npm ci` 和审计，逐项关闭 high 风险。

后端必须在候选 worktree 完成全量测试和隔离打包；UniApp 必须按正式 `scripts/build-mobile-candidates.ps1` 从干净候选、Node 20、各端真实 HTTPS API URL 构建。`mobile-api.invalid`、本地地址、未跟踪移动源码和未签名包均为阻断。

## 3. 默认只读 QA 预检

以下命令不会连接数据库、读取秘密或启动进程，且可安全生成计划收据：

```powershell
$evidence = "$worktree\output\release-evidence\<batch>"
& "$worktree\scripts\qa\Reset-QAReleaseDatabase.ps1" `
  -MySqlDatabase 'ai_profit_qa_<batch>' -MySqlUsername 'qa_release' -EvidenceRoot $evidence
& "$worktree\scripts\qa\Start-QAReleaseCandidate.ps1" `
  -JarPath "$worktree\backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar" `
  -MySqlDatabase 'ai_profit_qa_<batch>' -MySqlUsername 'qa_release' -EvidenceRoot $evidence
& "$worktree\scripts\qa\Initialize-QAReleaseFixtures.ps1" `
  -QaBaseUrl 'https://qa.example.invalid' `
  -FixtureSpecPath "$worktree\scripts\qa\qa-fixture-spec.template.json" -EvidenceRoot $evidence
```

这些脚本故意不在未授权时创建或清空数据库。

## 4. 经审批脱敏基线恢复

QA profile 故意禁用了演示数据和默认账号；因此空库重置后不能直接创建业务夹具。必须先恢复一个**经审批的脱敏基线**，再启动候选并写入 `E2E_` 夹具。基线 SQL 不得由生产库直接导出，必须在独立审查后放在工作树外的当前 Windows 用户目录；清单固定 SQL SHA-256、脱敏审查编号、`E2E_` 品牌代码，并要求至少一个启用的 QA BOSS。SQL 只允许导入空 QA 库，不能包含数据库切换、授权/用户管理、全局设置、文件读写、删除/更新、`DEFINER`、系统库引用或 MySQL 可执行注释。

默认预检不读取密码、不连接数据库：

```powershell
& .\scripts\qa\Restore-QAReleaseBaseline.ps1 `
  -MySqlDatabase 'ai_profit_qa_<batch>' -MySqlUsername 'qa_release' `
  -BaselineManifestPath "$env:LOCALAPPDATA\AI-Profit-OS\qa-baselines\approved-baseline.manifest.json" `
  -EvidenceRoot $evidence
```

恢复是独立于重置、候选启动、夹具写入的第三项授权；它只接受 loopback、非 3307、`ai_profit_qa_*`/`ai_profit_test_*`、非 root 的空数据库：

```powershell
& .\scripts\qa\Restore-QAReleaseBaseline.ps1 `
  -MySqlDatabase 'ai_profit_qa_<batch>' -MySqlUsername 'qa_release' `
  -BaselineManifestPath "$env:LOCALAPPDATA\AI-Profit-OS\qa-baselines\approved-baseline.manifest.json" `
  -Apply -AuthorizeQaBaselineRestore -EvidenceRoot $evidence
```

恢复收据保存基线 SHA-256、Flyway 版本、空库检查、必需表、品牌和 QA BOSS 合约；不保存 SQL 内容、密码或令牌。恢复后才允许启动候选，候选会用 Flyway 校验并升级基线。

## 5. 需要显式授权的 QA 写入流程

以下步骤会删除 **隔离 QA** 数据或启动会执行 Flyway 的候选；执行前必须取得发布负责人授权，并再次确认目标不是 3307：

```powershell
# 仅在已授权、已复核目标后执行。密码仅通过环境变量名读取。
& .\scripts\qa\Reset-QAReleaseDatabase.ps1 `
  -MySqlDatabase 'ai_profit_qa_<batch>' -MySqlUsername 'qa_release' `
  -Apply -AuthorizeQaReset -EvidenceRoot $evidence

& .\scripts\qa\Start-QAReleaseCandidate.ps1 `
  -JarPath '.\backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar' `
  -MySqlDatabase 'ai_profit_qa_<batch>' -MySqlUsername 'qa_release' `
  -Apply -AuthorizeQaCandidateStart -KeepRunning -EvidenceRoot $evidence

# 模板使用基线中的受控 E2E_ brandCode；不要手工猜测或填写数值 brandId。
# 将 batch、门店 ID/名称和用户名替换为实际 E2E_ 值后才允许执行。
& .\scripts\qa\Initialize-QAReleaseFixtures.ps1 `
  -QaBaseUrl 'https://<qa-host>' -FixtureSpecPath '.\scripts\qa\qa-fixture-spec.json' `
  -Apply -AuthorizeQaFixtureWrite -EvidenceRoot $evidence
```

重置收据包含重置前 Flyway 版本、逐表行数、MySQL 备份 SHA-256 和结果；夹具收据需含创建账号的操作日志。候选健康必须同时确认 `UP`、`QA`、目标数据库名、非 3307 端口和 Flyway 版本。

## 6. 验收与清理

1. 使用受控账号登录，验证六个角色工作台、菜单、直达路由、401、403、跨门店范围和登出。
2. 执行店长叫货→仓库发货→店长收货、财务录入→只读查看、巡检整改→运营复核、培训考试→员工学习闭环。
3. 对附件、PDF、视频 Range、AI/YOLO 正常和降级路径记录 HTTP 状态、请求 ID 和脱敏日志位置。
4. 通过 Kane 保存生成的测试和实际 NDJSON 结果；真机结果按设备矩阵归档。
5. 清理 QA 整库或签名/商店提交均须再次单独授权。未经授权，只保留计划收据和阻断说明。

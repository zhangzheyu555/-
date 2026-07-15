# UniApp 三端移动端一期预发布运行手册

## 0. 发布边界

本手册只覆盖 `mobile-uniapp` 的 H5、微信小程序、Android 和 iOS **预发布候选**。它不执行商店上传、正式发布、生产数据库连接、18081 实例替换或自动推广。

候选后端验证使用 `APP_ENV=QA`、本机隔离端口和名称含 `qa` 或 `test` 的独立 MySQL 数据库。这是“隔离预发布验证”，不是 `STAGING` 运行时：`DatabaseEnvironmentGuard` 对 `STAGING` 固定要求 127.0.0.1:3307 / `store_profit_mysql8`，而该实例属于受保护生产边界，绝不可用于本流程。

任何 P0 未通过时，结论必须写为：**禁止生产发布**。

## 1. 环境变量与密钥边界

所有值由当前 Windows 用户受控的环境变量、企业密钥系统或安全注入流程提供；仓库、构建日志、命令行、候选包、截图和证据文件中只允许出现变量名，不允许出现值。

| 用途 | 变量名 | 规则 |
|---|---|---|
| H5 预发布 API | `MOBILE_STAGING_H5_API_BASE_URL` | 绝对 HTTPS URL；不得包含用户信息、`mobile-api.invalid`、localhost 或回环地址。 |
| 微信小程序预发布 API | `MOBILE_STAGING_MP_WEIXIN_API_BASE_URL` | 独立配置项；需位于小程序合法请求域名白名单。 |
| Android 预发布 API | `MOBILE_STAGING_ANDROID_API_BASE_URL` | 独立配置项；真机必须可访问 HTTPS。 |
| iOS 预发布 API | `MOBILE_STAGING_IOS_API_BASE_URL` | 独立配置项；满足 ATS/HTTPS 要求。 |
| 隔离 QA 数据库密码 | `MOBILE_STAGING_MYSQL_PASSWORD` | 仅供候选 Java 进程瞬时使用；不得写到配置文件。 |
| 最小权限 QA 测试令牌 | `MOBILE_STAGING_LEAST_PRIVILEGE_TOKEN` | 只用于验证预期 403；不得放到 URL、日志或包内。 |
| 隔离 Redis 密码 | `MOBILE_STAGING_REDIS_PASSWORD` | 仅供候选 Java 进程访问本机隔离 Redis；不得写到配置文件、命令行或证据。 |

`mobile-api.invalid` 是候选构建 P0 阻断项。任何目标出现该占位地址、空 API 地址或非 HTTPS 地址，都不得形成 RC。

候选门禁脚本只接收密钥的环境变量名，不接收密码或令牌参数。若使用 Windows 用户加密配置或密钥系统，应在调用脚本前将值短暂注入当前进程环境；不要把明文或令牌放到命令历史、`-Command`、环境回显或截图中。

## 2. P0 预发布门禁

| P0 项 | 通过条件 | 证据 |
|---|---|---|
| 源码与候选一致性 | 候选从冻结提交、独立目录构建；JAR、H5、微信、Android、iOS 产物均有 SHA-256、版本、Node/npm、构建时间。 | `output/mobile-release-evidence/<run>/` |
| Node 20 LTS | 所有 UniApp 构建均由锁定的 Node 20 LTS 执行；Node 24 不能作为 RC 构建环境。 | 候选 manifest 与构建日志。 |
| API 配置 | 四端 API Base 分别注入，均为可访问 HTTPS，包内不存在 `mobile-api.invalid`。 | 分端 manifest、包内容扫描结论。 |
| 隔离后端 | 候选仅允许 QA/TEST、非 root、本机隔离 MySQL、非 3307、后端非 18081。 | `candidate-gate.json`。 |
| Flyway | QA 空库或专用隔离库的启动报告为 V54；不得把当前标准或生产库当作迁移证据。 | 隔离迁移记录、候选健康检查。 |
| 鉴权 | 无令牌 `/api/mobile/version`、`/api/auth/me`、`/api/stores` 都为 401；最小权限 QA 令牌访问老板接口为 403。 | `candidate-gate.json`，保留状态和 requestId，不保留令牌。 |
| 视频票据 | 本候选流程固定使用 Redis 共享票据存储；必须强制 `MOBILE_REQUIRE_SHARED_VIDEO_TICKETS=true`，不接受未验证的粘性会话或本地内存回退。网关与应用日志不记录 ticket 查询参数。 | 候选门禁、Redis 启动验证、网关脱敏配置、专项测试。 |
| 高危依赖 | `npm audit` 的高危项已修复或被书面接受；未可接受的 9 项高危依赖一律阻断 RC。 | 依赖审计报告和修复记录。 |
| App 签名 | Android/iOS 安装包必须由受控签名流程产生；未签名资源包只可用于内部构建验证，不能称为 App RC。 | 签名校验记录（不含证书）。 |

P1 包括 UI 文案、性能优化、非阻塞告警阈值优化和后续体验问题；P1 不得掩盖 P0。

## 3. 构建与隔离候选启动

### 3.1 构建前检查

1. 确认工作树和候选提交已冻结，并检查当前 Node 是 20 LTS。
2. 运行后端定向测试、全量测试和独立临时目录打包；分别运行 `frontend-vue` 与 `mobile-uniapp` 的类型检查和生产构建。
3. 运行移动候选构建脚本。四个 API Base 值仅由上述环境变量读取；构建输出只记录主机名和哈希，不记录完整 URL 中可能存在的路径或密钥。
4. 将每次输出写入一个新的、带时间戳的 `output/mobile-release-evidence/<run>/` 目录，禁止覆盖历史证据。

### 3.2 启动隔离候选

以下命令示例只包含变量名和非敏感示例库名；不要把密码或令牌写到命令行。

```powershell
.\scripts\start-mobile-staging-candidate.ps1 `
  -JarPath 'C:\path\to\store-profit-backend-0.1.0-SNAPSHOT.jar' `
  -ServerPort 18082 `
  -MySqlHost 127.0.0.1 `
  -MySqlPort 3308 `
  -MySqlDatabase ai_profit_mobile_qa `
  -MySqlUsername mobile_qa_app `
  -RedisHost 127.0.0.1 `
  -RedisPort 6380 `
  -KeepRunning
```

脚本的固定安全规则如下：

- 强制 `APP_ENV=QA`，数据库名必须含 `qa` 或 `test`。
- 硬拒绝 3307、18081、`root`、`store_profit_mysql8` 与生产式数据库名。
- Java 进程仅接收必要环境变量；继承的 password、token、secret、API key 等变量会先移除，再仅注入 QA 数据库密码。
- 强制 `MOBILE_REQUIRE_SHARED_VIDEO_TICKETS=true`，仅使用 `127.0.0.1:6380` 一类的专用非默认 Redis 端口；候选启动前要求 Redis 已在 loopback 监听，且只从 `MOBILE_STAGING_REDIS_PASSWORD` 读取密码。
- 先验证 `/api/health` 为 UP、环境为 QA、Flyway 为 V54、运行时数据库端口和名称匹配；再验证三项未登录 401 和最小权限令牌的 403。
- 只记录 HTTP 状态、requestId、JAR SHA-256 与无敏感字段的结论；不保存 Java 原始输出、认证头、密码或票据。
- 通过后最多保留 18082 的隔离实例供人工验收，输出 `MANUAL_PROMOTION_REQUIRED`；它不会调用部署或替换脚本。

未配置最小权限 QA 令牌时，跨权限 403 不可验证，候选门禁必须阻断，而不是跳过。

### 3.3 移动版本与后端验证

候选门禁通过只是后端启动证据，不等同于全终端验收。其后再使用每端各自的 API Base 进行真机矩阵测试。测试过程中：

- 401 必须自动退出到登录；403 必须保留中文业务提示且不泄露数据。
- `/api/mobile/version` 的请求不附带 ticket 或其他敏感查询参数。
- 附件上传和视频 Range 请求必须记录真实 HTTP 状态和 requestId；失败项给出设备、系统、网络、时间、复现步骤和脱敏日志位置。
- 浏览器仅允许 `mobile-uniapp/src/stores/session.ts` 保存登录会话；不得使用 localStorage 持久化业务数据。

## 4. 真机验收矩阵

所有单元先标记 `NOT_RUN`，只有实际设备、系统版本、App/微信版本、候选 SHA-256、网络条件、操作者和截图/视频链接齐全时才可标记 `PASS` 或 `FAIL`。

| 端 | 登录 / 401 / 403 | 店长叫货与收货 | 督导拍照上传与整改 | 员工视频 / 考试 / 助手 | 财务/老板只读摘要 | 专项 |
|---|---|---|---|---|---|---|
| H5（Android Chrome） | 必测 | 必测 | 必测 | 必测 | 必测 | 弱网、断网恢复、Range、附件、版本检查 |
| H5（iPhone Safari） | 必测 | 必测 | 必测 | 必测 | 必测 | 弱网、断网恢复、Range、附件、版本检查 |
| Android 微信 | 必测 | 必测 | 必测 | 必测 | 必测 | 微信更新、拍照/相册权限、上传超时 |
| iOS 微信 | 必测 | 必测 | 必测 | 必测 | 必测 | 微信更新、相机/相册权限、ATS |
| Android App | 必测 | 必测 | 必测 | 必测 | 必测 | 相机/相册权限、Range、断网恢复、版本检查 |
| iOS App | 必测 | 必测 | 必测 | 必测 | 必测 | 相机/相册权限、Range、断网恢复、版本检查 |

店长只验证所属门店数据范围；督导仅验证巡检/整改授权；员工仅验证学习和员工助手；财务和老板仅验证一期定义的只读摘要。跨门店、跨角色和附件越权必须返回 403，不应靠前端隐藏替代。

## 5. 视频票据、日志、监控与告警

### 5.1 视频票据与多实例

本候选流程明确选择 **Redis 共享票据存储**，不把粘性会话作为候选通过条件。候选启动脚本会强制 `MOBILE_REQUIRE_SHARED_VIDEO_TICKETS=true` 并要求本机隔离 Redis 可用；后端的共享票据启动探测失败时必须拒绝应用启动，绝不回退到 `ConcurrentHashMap`。

Redis 专项验收必须验证票据 TTL、一次性/重放语义、同一会话替换、Range 重连和 Redis 暂不可用时的拒绝行为。Redis 端口必须为专用非默认端口、只监听 loopback，密码只从 `MOBILE_STAGING_REDIS_PASSWORD` 注入。任何“仅配置粘性会话”“Redis 未验证”或“本地内存可用”的状态都是 P0 阻断项。

### 5.2 日志脱敏

- 网关 access log 禁止记录 `$args`、完整 request target 或 `ticket` 查询值；改记录路由模板、方法、状态、耗时和 requestId。
- 应用日志禁止记录 `Authorization`、Cookie、密码、token、ticket、上传原文件二进制和完整聊天内容。
- 日志查询、故障单和截图中只保留 requestId、时间范围、脱敏后的端点模板及文件/实例位置。
- 对历史可能泄露的密钥按事件处理：先撤销/轮换，再从后续日志和构建产物中隔离；不要把旧值复制到问题单。

### 5.3 监控与告警

| 信号 | 建议阈值 | 处置 |
|---|---|---|
| `/api/health` / Flyway 版本 | 非 UP、非 V54、数据库端口不符 | 立刻停止候选推广，保留证据。 |
| 移动 API 5xx | 5 分钟错误率超基线 | 检查 requestId 与实例日志，不记录认证头。 |
| 401/403 | 异常突增或跨门店 403 缺失 | 排查会话过期、权限发布和数据范围。 |
| 附件上传 | 超时、413、5xx、成功率下降 | 核对存储权限、大小限制与弱网重试。 |
| 视频 Range / ticket | 206 异常、403/404、Redis 失败 | 验证 Redis 共享票据、拒绝本地回退及日志脱敏。 |
| 版本检查 / 微信更新 | 版本接口错误或强更循环 | 立即回退版本配置，不上传商店。 |

## 6. 人工推广与回滚

### 6.1 人工推广前的签字清单

只有所有 P0 为 PASS，才由发布负责人明确确认候选 SHA-256、V54、真机矩阵、依赖审计、签名状态、密钥轮换状态和回滚版本。`start-mobile-staging-candidate.ps1` 的 PASS 不会也不能自动推广。

### 6.2 回滚

- 后端：停止 18082 隔离候选；保持 18081 与生产数据库不变。若已完成受控预发布部署，只能使用已审阅的版本目录和现有回滚脚本切回上一个已验证哈希，禁止复制覆盖运行中 JAR。
- H5：将预发布路由/静态版本切回上一个已验 SHA-256 版本，并清理 CDN/浏览器版本缓存策略后复测版本接口。
- 微信小程序：在微信后台由人工切回上一个审核通过的版本；本流程不提交、不上传、不发布。
- Android/iOS：停止分发当前候选，恢复上一个已签名且已验 SHA-256 的内部测试包；不撤销或上传任何商店版本。

回滚后重新记录健康检查、版本检查、失败 requestId、影响范围和回滚操作者。密钥疑似泄露时，轮换优先于恢复流量。

## 7. 交付物和结论模板

每轮在 `output/mobile-release-evidence/<run>/` 生成或汇总：

- `SHA256SUMS` 与每端 manifest；
- Node/npm/版本/构建时间记录；
- `candidate-gate.json`（无密码、令牌、ticket、Authorization）；
- Flyway V54 记录；
- 真机矩阵、失败复现、requestId 和脱敏日志位置；
- 依赖审计、签名状态、遗留风险和最终结论。

结论只允许使用：`RC`（全部 P0 PASS）、`BLOCKED`（至少一项 P0 未通过）或 `NOT_RUN`（尚未验收）。`BLOCKED`/`NOT_RUN` 时必须写明：**禁止生产发布**。

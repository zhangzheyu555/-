# AI Profit OS 后端风险整改执行策划书（关卡制）

> 状态：执行中（R1-01、R1-02 已验证完成；D1/D2 已由 V2 重新签署关闭；R1-03 待开发）  
> 来源：`C:/Users/34706/Documents/xwechat_files/wxid_zgw781jjm8h522_0eed/msg/file/2026-07/backend-risk-remediation-plan.md`  
> 维护者：Codex；每个工作包完成后由实施者更新本文件。  
> 最后更新：2026-07-16

## 1. 目标与不可违反的原则

本策划书把《后端风险整改实施方案》拆成可验证的小关卡。目标不是一次性重构，而是按顺序消除会造成权限、库存、账务或跨租户事故的风险。

- 只开发 `frontend-vue` 与 `backend`；MySQL 是唯一业务事实来源。
- 不改写已执行 Flyway 迁移；所有数据库变更只新增迁移或可审计运维工具。
- `BOSS` 是唯一最高业务角色；`ADMIN`、`OWNER` 只允许作为历史迁移输入。
- 真实生产、3307、root 用户、生产账户、生产数据均不用于验证。系统测试只在授权的隔离 QA MySQL 8 环境执行。
- 每次只允许一个风险工作包处于“开发中”或“待系统验证”。除非该包成为“已验证完成”，否则不得开始其后继包。
- 不以 H2、Mock、启动成功或页面截图代替真实 MySQL、HTTP 权限和业务一致性验证。

## 2. 状态定义与放行规则

| 状态 | 含义 | 是否可解锁下一包 |
| --- | --- | --- |
| 设计中 | 已在澄清边界、数据模型、契约和测试；未改风险代码 | 否 |
| 待开发 | 设计已冻结，等待实施 | 否 |
| 开发中 | 正在实施，尚未完整验证 | 否 |
| 待系统验证 | 单元测试和构建已通过，等待隔离 MySQL/QA 验收 | 否 |
| 已验证完成 | 代码、迁移、构建、真实 MySQL、接口或 E2E 证据均已记录 | 是 |
| 阻塞 | 缺少业务决策、QA 授权或外部条件；必须写明阻塞项 | 否 |

每个风险工作包必须按下列顺序执行并记录证据：

1. 设计冻结：列出受影响业务不变量、接口、数据表、旧数据兼容和回退方式。
2. 最小实现：不混入其他工作包或用户已有改动。
3. 本地验证：后端 `mvn -q test`、`mvn -q -DskipTests package`；涉及前端时追加 `npm run build`。
4. 数据库验证：在临时、隔离的 MySQL 8 空库和前向升级库执行 Flyway；不得使用生产库或 3307。
5. 系统验证：启动隔离 QA 候选，验证健康检查、401/403、关键流程、操作日志和业务对账。
6. 证据归档：命令、版本、日志位置、HTTP 状态、Flyway 版本和对账结果写入本文件及 `output/release-evidence/<发布包>/`。
7. 状态更新：仅在全部门禁通过后标记“已验证完成”，再把下一包从“锁定”变为“待开发”。

## 3. 当前基线（仅事实，不等于整改完成）

### 3.1 已完成的规划性工作

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| R1-00：设计与代码基线盘点 | 已完成（规划项，不属于风险消除） | 2026-07-15 已完成只读核查；`backend/mvn -q test` 退出码为 0。 |
| 生产风险整改 | R1-01、R1-02 已验证完成；R1-03 待开发 | R1-02 最终候选已通过全量测试、隔离 MySQL 8、双 CLI 并发、真实 HTTP、秘密扫描和清理门禁；D1/D2 已于 2026-07-16 通过 `d1-d2-signed-decision-v2.md` 重新签署关闭。 |

### 3.2 已识别的 R1 风险

- 现行认证入口是 `platform/auth/AuthService.java`，不是来源方案列出的 `AppAuthService`。整改前存在 `@PostConstruct ensureDefaultUsers()` 与 `StoreManagerAccountSeedService` 两条启动建号路径；R1-01 已从活动源码中删除，并已由隔离 MySQL 8/QA 候选证明运行时无建号副作用。
- `app.bootstrap.default-users-*` 与 `app.bootstrap.store-manager-*` 及对应环境变量绑定已从活动配置、CI 和启动脚本删除；旧变量即使残留在外部运行环境也已无代码绑定，不能触发建号。
- 未发现硬编码 `BOOTSTRAP_PASSWORD = "123"` 或 HTTP 首管理员创建接口；`AuthController` 仍仅提供登录、登出和当前用户接口。
- `V3` 写入默认租户，`V4` 写入 5 个演示商品和 `*-SEED` 演示库存，`V7` 写入“总部默认供应商”。这些已执行迁移绝不能改写。
- `app.migration.auto-run`、运行时组织/财务 Seed 仍需要生产环境强制拒绝；`DatabaseRuntimeIdentityGuard` 当前为未完成实现，不能作为已生效保护。
- 当前主 MySQL 迁移版本为 V56；V56 是用户已有未提交改动，任何后续 R1 迁移在实施时必须重新确认下一个可用版本，不能抢占或覆盖版本号。
- 当前 Maven 测试主要依赖 H2/Mock；没有 Testcontainers MySQL 8 覆盖。H2 迁移链也并非与 MySQL 完全对齐，因此不能证明生产迁移与并发正确性。

### 3.3 已知发布与环境阻塞

- 当前工作区有与 AI 助手、巡检和 V56 相关的用户未提交修改；整改工作不得修改、回滚或混入它们。
- 隔离 QA 的 E2E 所需 `E2E_BASE_URL`、`E2E_API_URL` 和受控角色凭据尚未提供；未获得明确授权前不得重置 QA 库、启动候选或写入夹具。
- 现有 CI 脚本把 Flyway 最新版本硬编码为 56；任何新增迁移都必须同步更新该门禁及相应验证脚本。

## 4. 需要先固定的设计决策

| 编号 | 决策 | 默认设计 | 关闭条件 |
| --- | --- | --- | --- |
| D1 | 历史 Seed 的处理边界 | **已关闭（2026-07-16 重新签署）**：A 认可确定性谱系；B/G 仅在全部硬条件满足后成为候选；C/D/E/F 保留并安全停用；H/I 原样保留；J 排除于自动处置。 | 有效签署：`output/release-evidence/R1-03-decision-freeze-20260715/d1-d2-signed-decision-v2.md`（SHA-256 `88483578A2D7A99756987ACF69E126EBFAD312441988FC2CA5CD71413FAB2AD3`）；确认来源：同目录 `signoff-confirmation-source.md`。旧 `d1-d2-signed-decision.md` 仅作错误时间审计历史。 |
| D2 | 空库严格无 Seed | **已关闭（2026-07-16）：选择 A“最终状态清理”**。B“新生产基线”不进入本工作包；若未来出现迁移过程不得写入 Seed 的强制合规要求，必须重新打开 D2。 | 同上 V2 签署文件；D2=A 不等于生产数据处置授权。 |
| D3 | 首管理员初始化 | 只允许一次性非 Web 运维命令；密码从部署密钥环境变量或安全标准输入读取。每次执行必须提供真实运维执行人和工单号；`first_boss_provisioned` 安全初始化审计在租户生命周期内永久保留，不纳入常规操作日志清理。 | **已关闭（2026-07-15）**；R1-02 已验证执行人/工单必填、秘密不落日志、并发仅创建一次及该动作无删除路径。 |
| D4 | 正式库名与网络安全 | 统一正式数据库命名、TLS、CORS 正式域名和 HTTPS 网关策略，不能为绕过守卫而放宽规则。 | 发布负责人确认唯一正式环境配置。 |

## 5. 总体工作包看板

| 工作包 | 来源阶段 | 目标 | 当前状态 | 前置关卡 |
| --- | --- | --- | --- | --- |
| R1-01 | P0 | 移除应用启动自动建号 | 已验证完成 | R1-00 |
| R1-02 | P0 | 一次性首管理员 CLI 与安全审计 | 已验证完成 | R1-01 已验证完成 |
| R1-03 | P0 | 历史 Seed 审计、清理边界与生产 Seed 阻断 | 待开发 | R1-02 已验证完成、D1/D2 已由 V2 关闭（2026-07-16） |
| R1-04 | P0 | 生产启动安全守卫、R1 MySQL/QA 验收 | 锁定 | R1-03 已验证完成、D4 已决策 |
| R2 | P0 | 库存原子扣减、发货状态机、发货幂等和库存对账 | 锁定 | R1 已验证完成 |
| R3 | P1 | 收货、退货、采购收货状态机与幂等 | 锁定 | R2 已验证完成 |
| R4 | P1 | 报销、工资、利润、巡检并发控制 | 锁定 | R1 已验证完成 |
| R5 | P1 | Spring Security、统一认证上下文与 Token 加固 | 锁定 | R1 已验证完成 |
| R6 | P2 | 租户数据库约束、复合外键和数据审计 | 锁定 | R5 已验证完成、租户审计无阻断项 |
| R7 | P3 | 停止 legacy KV 双写 | 锁定 | 对应正式模块 R2–R6 已验证完成 |
| R8 | P3/P4 | 分布式任务、附件、外部服务和可观测性 | 锁定 | R5 已验证完成 |
| R9 | P4 | 分页、索引、容量与 200 门店验收 | 锁定 | P0–P2 已验证完成、慢任务已异步 |

## 6. R1 详细设计与 Codex 实施提示词

### R1-01：禁止应用启动自动创建账号

**设计思路**

移除所有可通过应用环境变量在 Web 服务启动时创建业务账号的 `@PostConstruct` 路径。登录、用户管理和既有账号认证必须保持可用，但空库调用登录接口不得创建任何账号。此任务不实现 Token 哈希、`tenantCode` 登录或 Spring Security；它们属于 R5，避免把 P0 与大范围鉴权重构混在一起。

**最小变更边界**

- 修改 `platform/auth/AuthService`，删除默认账号 bootstrap 注入、`ensureDefaultUsers()` 与相关兼容测试路径。
- 让 `StoreManagerAccountSeedService` 不再作为运行时 Spring Bean 自动创建账号；迁移用途只能留在后续一次性命令/工具中。
- 删除 `app.bootstrap.default-users-*` 与 `app.bootstrap.store-manager-*` 的运行时配置和调用绑定，使旧变量无法触发任何建号逻辑；通用生产危险配置启动守卫仍按 R1-03/R1-04 边界实施。
- 不改现有 Flyway 文件、不更改用户已有 V56 工作。

**验收门禁**

- 单元/架构测试证明：生产 Bean 中不存在创建账号的 `@PostConstruct`。
- 空 MySQL 8 下启动后，调用所有登录接口前后 `auth_user` 行数不增加。
- 无 Token 的账号管理接口返回 401；既有已启用 QA 用户仍能登录、登出后 Token 失效。
- 后端测试、打包与隔离 QA HTTP 验收均通过后，才更新为“已验证完成”。

**Codex 实施提示词**

```text
实施 R1-01：禁止 AI Profit OS 在 Web 服务启动时自动创建任何业务账号。

先读取 AGENTS.md 和 docs/backend-risk-remediation-execution-plan.md，只处理 R1-01；不得开始 R1-02 或后续工作包。保留用户已有未提交修改，不得修改已执行 Flyway 迁移。

以现有 platform/auth/AuthService 和 StoreManagerAccountSeedService 为准，不使用来源方案中已不存在的 AppAuthService 路径。删除默认用户和店长账号的 @PostConstruct/运行时 bootstrap 路径，并删除或拒绝 app.bootstrap.* 运行时配置。登录只能认证已存在账号，空库登录绝不能建号。保持 BOSS 为唯一最高角色。

补充最小单元/架构测试和真实 MySQL 8 集成测试：空库登录前后 auth_user 数量不变；无 Token 账号管理返回 401；既有用户登录和登出回归通过。执行 mvn -q test 和 mvn -q -DskipTests package。随后仅在已授权的隔离 QA 库启动候选并记录 Flyway、HTTP 状态和日志；不得连接 3307 或生产库。

将代码、测试、命令、结果、未验证项和证据路径写回本策划书的 R1-01 条目。任何门禁失败时标记“阻塞”，不要开始 R1-02。
```

#### R1-01 完成记录（2026-07-15）

- 状态：**已验证完成**。本地代码、后端测试/打包、候选 JAR 审计、前端构建、隔离 MySQL 8 空库迁移、QA 候选真实 HTTP 与数据对账门禁均已通过。
- 代码与迁移：
  - 认证代码：修改 `backend/src/main/java/com/storeprofit/system/platform/auth/AuthService.java`，删除默认账号 bootstrap 字段、`@Value`、兼容构造参数、`@PostConstruct ensureDefaultUsers()` 和 `ensureDefaultUser()`；删除 `StoreManagerAccountSeedService.java`，正常 Web 运行时不再存在店长批量建号能力。
  - 配置与发布：修改 `backend/src/main/resources/application.yml`、`application-qa.yml`、`.github/workflows/vue3-ci.yml`，删除默认账号/店长账号 bootstrap 配置；同步修改 `db/migration-h2/README.md`、`docs/vue3-production-deployment.md` 以及 `scripts/mysql8-cutover.ps1`、`scripts/qa/Start-QAReleaseCandidate.ps1`、`scripts/start-backend-assistants-secure.ps1`、`scripts/start-backend-windows.ps1`、`scripts/start-salary-test-runtime.ps1`、`scripts/validate-v54-isolated-mysql.ps1`、`scripts/verify-inspection-standard-v33.ps1`、`scripts/verify-v43-production-recovery.ps1`。依赖旧 bootstrap 的验证脚本现只接受既有受控 BOSS，空库明确失败，不创建替代账号。
  - 测试：新增 `AccountBootstrapRemovalContractTest.java`、`AuthServiceLoginTest.java`；修改 `AuthServiceAuthorizationContractTest.java`、`AuthServiceLogoutTest.java`、`MobileApiDatabaseContractTest.java`、`UserControllerAuthorizationContractTest.java`；删除正向验证启动建号的 `AuthServiceBootstrapTest.java` 与 `StoreManagerAccountSeedServiceTest.java`。
  - Flyway：未新增或修改任何迁移。V1～V56 保持不变；当前候选 JAR 打包的最新版本为用户既有 V56。
- 业务不变量：Web 应用启动和任何登录失败路径都不能创建 `auth_user`；只有已存在且启用、密码匹配的账号可获得 Token；错误密码和停用账号返回 401；登录失败不写 Token 或建号操作日志；登出后 Token 失效；`AuthController` 仅保留 login/logout/me；正式账号管理仍先认证并要求 BOSS；BOSS、财务、店长、仓库和运营现有权限解析代码未改变。
- 后端测试与打包：
  - `cd backend && mvn -q test`：2026-07-15，退出码 0；143 个 Surefire 报告、665 个测试、0 failure、0 error、0 skipped；日志 `output/release-evidence/R1-01-20260715/mvn-test.log`，汇总 `maven-test-summary.txt`。
  - `cd backend && mvn -q -DskipTests package`：2026-07-15，退出码 0；日志 `output/release-evidence/R1-01-20260715/mvn-package.log`。
  - 候选 JAR 审计：`StoreManagerAccountSeedService.class` 不存在，打包后的 `application.yml` 不含已移除配置，结果 PASS；证据 `jar-content-audit.txt`。
- 前端构建：`cd frontend-vue && npm run build`（包含 `vue-tsc -b` 与 Vite 生产构建），退出码 0；日志 `frontend-build.log`。本次未修改页面，无需新增桌面/移动端视觉验收。
- 隔离验证工具：新增 `scripts/validate-r1-01-account-bootstrap-removal-isolated-mysql.ps1`。脚本拒绝 3306、3307、18081，使用随机临时库、随机数据库账号和仅回环监听；结束时只按本次临时目录识别并停止自有进程，删除临时数据。
- MySQL 8 空库验证：使用本机 MySQL Community Server **8.0.46**，在 `127.0.0.1:3311` 创建全新临时数据目录和空业务库；候选 JAR 在 QA profile 下将 Flyway 执行至 **V56**，失败迁移 0。验证结束后数据库进程已停止、临时目录已删除。
- HTTP/系统验证：候选 JAR SHA-256 为 `D50AD328AE452154F06355D0FC8D4E125317BFB7D7B6DA47908CB48B8BABEFF2`，临时运行于 `http://127.0.0.1:18121`。`/api/health` 返回 200；未知账号登录以及无 Token 的 `/api/auth/me`、`/api/users`、`/api/auth/logout` 均返回 401；受控 BOSS 登录、me、users、logout 均返回 200，复用已登出 Token 返回 401。验证结束后候选进程已停止，18121 已释放。
- 数据对账：即使把已删除的旧 bootstrap 环境变量显式设为 `true` 且旧密码设为 `123`，启动后仍为 `auth_user=0`、`auth_token=0`、`operation_log=0`；负向 HTTP 后三项仍为 0。仅在上述断言通过后插入一次性受控 BOSS 夹具：登录后为 `1/1/0`，登出后为 `1/0/1`，新增且仅新增一条 logout 审计。
- 隔离边界：系统验证未连接或停止 3306、3307、18081；保留端口监听前后完全一致。3311、18121 验证后均无监听，临时运行目录无残留。
- 证据目录：`output/release-evidence/R1-01-20260715/`。系统验收主证据为 `r1-01-isolated-mysql-20260715-191033.json` 与同名 `.md`；首次 `190723` 证据因退出清理等待不足为 FAIL，仅作为修复前历史，不作为放行依据。
- 残余项：本验收只证明 R1-01 的账号 bootstrap 移除，不代表 R1-03 的历史业务 Seed 已治理。用户既有未跟踪 `scripts/validate-v56-column-comments-isolated-mysql.ps1` 中两个值为 `false` 的旧变量名及未重算的 `PACKAGE_MANIFEST.sha256` 未修改，不构成活动建号路径。Surefire 中一个历史 XML 报告格式异常，但 Maven 汇总与退出码为 665 个测试、0 failure/error/skipped；后续发布归档可单独修复报告格式。
- 放行结论：**解锁 R1-02 为“待开发”**。R1-01 所需代码、本地构建、真实 MySQL 8、真实 HTTP、数据库计数、审计、隔离和清理证据均已通过；R1-03 及以后工作包继续锁定。

### R1-02：一次性首管理员 CLI 与安全审计

#### R1-02 设计冻结记录（2026-07-15）

- 前置关卡：已完整复核 R1-01 `191033` PASS 主证据；R1-01 为“已验证完成”，R1-02 已获准开发，R1-03 保持锁定。
- Flyway：当前 MySQL 迁移连续为 V1～V56；V56 是用户已有未跟踪文件。本包不新增迁移，也不修改 V1～V56；基线哈希见 `output/release-evidence/R1-02-20260715/flyway-baseline-sha256.txt`。
- 启动分流：无精确 `--admin-bootstrap` 才走原 Web 路径；存在精确子命令但启用开关非精确 `true` 时，为满足固定退出码 3 和零写入安全要求，不执行命令、不创建 Spring、直接非 Web 退出；开关启用后才执行普通 Java `AdminBootstrapCommand`。CLI 模式拒绝所有额外参数，尤其密码参数。
- 数据安全：复用抽取后的数据库环境规则，叠加真实 JDBC 身份、授权和 V1～V56 完整性只读校验；CLI 不运行 Flyway。租户按 id 锁定后在 Java 中精确核对 `ACTIVE` 与未裁剪的名称确认值。
- 一次性事务：锁定租户和该租户账号范围；账号非空或已存在 `first_boss_provisioned` 永久审计均拒绝；账号与审计同事务，审计失败整体回滚，不创建 Token、角色权限或数据范围。
- 密码与输出：PBKDF2 新增 `char[]` 入口且保持登录兼容，调用方与临时数组清零；仅输出固定语义结果，禁止秘密、完整参数、连接信息和异常详情进入任何输出或证据。
- 详细冻结内容：`output/release-evidence/R1-02-20260715/design-freeze.md`。

#### R1-02 完成记录（2026-07-15）

- 状态：**已验证完成**。设计、代码、无旧报告污染的全量测试、后端打包、前端生产构建及最终候选的隔离 MySQL 8 系统验收全部通过。
- 修改文件：`backend/src/main/java/com/storeprofit/system/StoreProfitApplication.java`、`config/DatabaseEnvironmentGuard.java`、`config/DatabaseIdentityValidator.java`、`config/DatabaseRuntimeIdentityGuard.java`、`platform/auth/PasswordService.java`、`platform/bootstrap/AdminBootstrapCommand.java`、`AdminBootstrapPasswordPolicy.java`、`AdminBootstrapPasswordSource.java`、`AdminBootstrapResult.java`；对应 `StoreProfitApplicationDispatchTest`、数据库守卫测试、`PasswordServiceCharArrayTest` 与 `platform/bootstrap` 单元/架构测试；`scripts/validate-r1-02-bootstrap-admin-isolated-mysql.ps1`；本策划书及 `output/release-evidence/R1-02-20260715/` 证据。
- 验收夹具修复：首次系统失败后，仅修正 R1-02 隔离脚本的两处缺陷：秘密扫描输入过滤尚未生成的空值并只记录安全失败码；应用账号的数据库级 `GRANT` 对随机库名中的 `_` 做精确转义，避免通配授权触发产品安全预检。未放宽 Java 数据库身份/授权校验。
- 迁移与 Flyway：R1-02 未新增迁移，V1～V56 均未修改；56 个迁移哈希与开始基线一致，最终空库为 V56、失败迁移 0，候选 JAR 不含 V57 或更高迁移。
- 业务不变量：正常无子命令 Web 路径不构造 CLI；精确子命令在开关非精确 `true` 时非 Web 退出 3；CLI 包不是 Spring Bean/Runner，不创建 Spring/Web/Flyway；只有 `main` 调用 `System.exit`。命令按租户 ID 锁定并精确核对 ACTIVE/名称，只允许空租户创建一个 `BOSS`，账号和 `first_boss_provisioned` 审计同事务，失败回滚，不创建 Token、角色权限或数据范围；密码 `char[]` PBKDF2 保持现有登录兼容并清理临时数组。
- 本地测试：`cd backend && mvn -q clean test` 最终退出码 0；148 个本轮 Surefire XML 报告、688 个测试、0 failure、0 error、0 skipped。首次 `clean` 因 `target` 内模板副本继承只读属性而未进入测试；仅清除该生成副本的只读标记后从 `clean test` 完整重跑。
- 构建：`cd backend && mvn -q -DskipTests package` 退出码 0；`cd frontend-vue && npm run build`（含 `vue-tsc -b` 和 Vite 生产构建）退出码 0。
- 最终候选：`backend/target/store-profit-backend-0.1.0-SNAPSHOT.jar`，SHA-256 `26F1457425B9741A8CC8FF4136F3856A25CBD25DAC51CB64DB098706C3BA893C`。
- 隔离 MySQL 8/Flyway：使用随机私有端口、临时数据目录、随机库和非 root 精确库级授权账号；Flyway 完成后 `auth_user/BOSS/first_boss_provisioned/auth_token/operation_log=0/0/0/0/0`，随后停止迁移 Web 候选再执行 CLI。
- CLI 并发与重复执行：最终双进程退出码为 `5/0`，恰好一个成功；CLI TCP 监听观测为 0。并发后 `auth_user/BOSS/first_boss_provisioned/auth_token/operation_log=1/1/1/0/1`；重复执行退出码 5 且计数不变。
- HTTP/数据对账：正常 Web 候选的 health/login/me/users/logout 状态均为 200，复用已登出 Token 为 401；登出后 `auth_user/BOSS/first_boss_provisioned/auth_token/operation_log=1/1/1/0/2`，只新增一条登出审计。
- 秘密与证据扫描：21 个运行日志、`operation_log`、26 个源码差异文件和 12 个证据文件全部通过秘密扫描；HTTP 证据仅保留元数据；源码差异扫描和证据自检均为 PASS。
- 隔离与清理：3306、3307、18081 未被使用或停止；受保护监听前后 PID/地址一致，`productionPortsTouched=false`。自有 MySQL、Java 进程全部停止，临时目录删除，无 `ai-profit-r1-02-isolated-*` 残留。当前候选服务地址：**未启动**。
- 主证据：本地构建汇总为 `output/release-evidence/R1-02-20260715/local-build-validation-20260715.md`；系统验收为 `r1-02-isolated-mysql-20260715-231058-31a13b62.json` 与同名 `.md`。`system-validation-failure-20260715-200733.md` 及中间诊断证据仅保留故障历史，不作为放行依据。
- 工作树保护：未提交、未推送、未回滚；用户原有未提交修改及 V56 均保留。
- 历史残余风险（R1-02 于 2026-07-15 完成时）：R1-02 已闭环，但不代表历史业务 Seed 已治理，也不代表 R1-04 的生产数据库身份/TLS/CORS 守卫已完成；当时 D1/D2 尚无书面选择。该历史状态已被 2026-07-16 的 V2 重新签署取代。
- 历史放行结论（R1-02 于 2026-07-15 完成时）：R1-02 已放行，R1-03 当时继续锁定并等待 D1/D2。**当前有效结论以第 4、5、9 节为准：D1/D2 已关闭，R1-03 待开发。**

**设计思路**

以独立、非 Web 的一次性运维命令创建首个 `BOSS`。正常 JAR 主程序先识别精确的 `--admin-bootstrap` 子命令；只有该子命令与 `APP_BOOTSTRAP_ADMIN_ENABLED=true` 双重满足时才进入纯 JDBC 命令，且在创建任何 Spring Context、Web Server、调度器、Seed Bean 或外部客户端之前完成并退出。正常 Web 启动路径保持不变。

来源方案要求“租户编码”，但当前 V1～V56 的 `tenant` 只有 `id/name/status`，没有 code。R1-02 冻结的等价身份方案为：强制同时提供数值 `tenantId` 与现有租户名称的精确确认值，锁定该 ACTIVE 租户行后再操作；绝不回退默认租户 1、模糊匹配名称或为本工作包猜测/批量回填租户编码。正式 `tenantCode` 模型留在 R5 的多租户登录设计中。若实施者要改为新增 `tenant.code`，必须先暂停 R1-02，单独形成 append-only 迁移、历史映射和回滚兼容设计并更新本策划书后才能编码。

命令只允许在目标租户尚无任何 `auth_user` 时创建首账号；非空租户或已有 BOSS/历史 `ADMIN`/`OWNER` 一律按人工恢复场景拒绝，不能借初始化命令追加或提权账号。密码只从部署密钥环境变量或安全控制台/标准输入读取，复用 PBKDF2，并在使用后清理内存。账号插入与 `operation_log` 安全初始化审计必须在同一事务；审计记录真实运维执行人和工单号，`first_boss_provisioned` 在租户生命周期内永久保留且不得进入常规日志清理；成功后不签发 Token。

**验收门禁**

- 缺开关、缺参数、弱密码、租户不存在/停用/名称确认不符、非空租户均以固定非零退出码失败，`auth_user/auth_token/operation_log` 无变化。
- 成功时仅创建一个 `BOSS`（`store_id=null`、`enabled=1`、`permission_version=1`）、写入一条无秘密的初始化审计、进程全程不启动 Web 端口且不执行 Flyway。
- 真实 MySQL 8 上以两个不同用户名并发启动两个 OS 进程，必须恰好一个成功、一个因租户已初始化失败；最终一个 BOSS、一条初始化审计、零 Token。
- 重复执行拒绝后计数不变；随后用正常 Web 候选验证新 BOSS 可登录/登出且旧 Token 失效。
- 密码原文、密码哈希、数据库密码、Token 和部署密钥不得出现在参数、标准输出、错误、应用日志、操作日志或发布证据中。

**历史 Codex 实施提示词（仅保留 R1-02 实施记录，非当前操作指令）**

```text
实施 R1-02：为 AI Profit OS 创建一次性、非 Web 的首 BOSS 初始化命令。

工作目录：C:\Users\34706\Documents\Codex\2026-07-10\new-chat\outputs\AI-Profit-OS-Qoder-Source-20260710-112707

[历史指令] 前置条件：R1-01 在 docs/backend-risk-remediation-execution-plan.md 中已标记“已验证完成”。先读取 AGENTS.md、R1-01 完成记录及 output/release-evidence/R1-01-20260715 的 PASS 主证据；只实施 R1-02，不得开始 R1-03，不得重新引入 @PostConstruct、CommandLineRunner、ApplicationRunner、Spring Bean Seed 或 HTTP 建号路径。保留全部用户既有未提交修改，不提交、不推送、不回滚；绝不改写 V1～V56，开始前重新确认最新 Flyway 版本。

按当前真实结构实施“主程序前置分流 + 纯 JDBC 命令”：StoreProfitApplication.main() 在创建 SpringApplication 之前，只在同时出现精确 --admin-bootstrap 子命令和 APP_BOOTSTRAP_ADMIN_ENABLED=true 时调用 AdminBootstrapCommand 并按结果退出；否则完全走原 Web 启动。CLI 代码放到 platform/bootstrap，禁止任何 Spring stereotype/runner/@PostConstruct，禁止 SpringApplication.run、Flyway migrate、Web Server、调度器、Redis/AI/平台客户端。业务层只返回固定退出码，只有 main 边界可 System.exit。

当前 tenant 表没有 code。本包采用已冻结的等价身份方案：必填 APP_BOOTSTRAP_ADMIN_TENANT_ID 与 APP_BOOTSTRAP_ADMIN_TENANT_NAME_CONFIRM，按 id 锁定 ACTIVE tenant 后要求名称精确一致；不得默认 tenant 1、不得按名称模糊查找、不得在本包私自增加或猜测 tenant code。若认为必须新增 tenant.code，先停止实现并把 append-only 迁移、历史映射和兼容方案写回策划书，未经设计冻结不得编码。

输入还必须包含规范化用户名、显示名、运维执行人和工单/原因。用户名沿用 [a-z0-9_.-]{3,40}；密码 12～128 位并满足大写、小写、数字、符号，拒绝 123、常见弱密码及包含用户名的密码。密码只能从部署密钥环境变量或不回显的安全控制台/标准输入读取，禁止 --password=<值>、配置文件、前端或日志传递；尽量以 char[] 处理并在 finally 清零，扩展 PasswordService 的 char[] PBKDF2 入口且保持现有登录兼容。

CLI 用现有 MYSQL_* / APP_ENV 配置建立单个 JDBC 连接，复用或抽取 DatabaseEnvironmentGuard 的数据库身份/TLS/root/环境规则，不能绕过或放宽；只接受已由 Flyway 迁移且无失败记录的 schema，CLI 自身不迁移。一个事务内：SELECT ACTIVE tenant FOR UPDATE 并核对名称；SELECT 该 tenant 的 auth_user 范围 FOR UPDATE；只在总数为 0 时插入一个 role=BOSS、store_id=null、enabled=1、permission_version=1 的用户并取得主键；再写一条 operation_log（operator_id=null，operator_name=真实运维执行人，action=first_boss_provisioned，target_type=auth_user，target_id=新用户 id，reason=工单号/原因，after_json 仅含非敏感账号元数据）；审计失败必须整体回滚。该动作记录在租户生命周期内永久保留，任何常规日志清理都必须排除它。不得创建权限/门店范围行，不得签发 Token。

固定并测试退出码：0=创建成功；2=参数/密码策略错误；3=未显式启用、环境或 schema 安全校验失败；4=租户不存在/停用/名称不符；5=租户已有账号或已初始化；6=锁等待/并发失败；7=数据库事务失败；70=未预期异常。所有输出仅给语义化结果，不回显环境变量、连接串、密码、hash、Token 或完整参数。

补齐单元/架构测试：正常 Web 路径不触发 CLI；CLI 包不加载 Spring Context；缺 enable/参数/密码、弱密码、租户异常、非空租户均零写入；成功为一个 BOSS+一条审计+零 Token；审计失败时账号回滚；日志脱敏。执行 mvn -q test、mvn -q -DskipTests package 及 frontend-vue/npm run build。

新增 R1-02 专用隔离验证脚本，沿用 R1-01 的安全边界：只使用随机临时 MySQL 8 数据目录、随机数据库账号、QA profile 和非 3306/3307/18081 端口；不得连接或停止现有监听。先迁移空库并停止 Web，再以两个不同用户名同时启动两个真实 CLI 进程，断言恰好一个退出 0、另一个退出 5/6，最终 auth_user=1、BOSS=1、operation_log 中 first_boss_provisioned=1、auth_token=0；重复执行后计数不变。监测 CLI 全程没有新 Web 监听。随后正常启动候选，验证 health 200、BOSS login/me/users/logout 200、登出 Token 复用 401。扫描全部输出和证据，确认不含原密码、hash、数据库密码或 Token；最后停止自有进程并删除临时数据，确认保留端口前后不变。

[历史指令] 把设计、文件、命令、退出码、JAR SHA-256、Flyway 版本、MySQL/HTTP/数据库计数、脱敏扫描、清理结果和证据路径写回 R1-02 完成记录。只有所有门禁真实 PASS 才把 R1-02 标为“已验证完成”；R1-03 在当时还必须等待 D1/D2 书面决策。该等待条件已由 2026-07-16 V2 签署关闭。
```

### R1-03：历史 Seed 审计、清理边界与生产 Seed 阻断

**设计思路**

遵守 append-only Flyway，以 D1/D2 V2 为唯一现行处置边界。先建立只读扫描、稳定指纹、引用/流水/附件/人工修改判据和审计状态，再允许执行任何变更。D2 已选择 A：保留 V1～V56，以后续追加迁移和受控治理保证最终状态没有可用演示数据；不得改写旧迁移，也不得把该选择解释成生产删除授权。

D1-A 只确认谱系；D1-B 的五个 `-SEED` 批次必须与 V43 库存投影整体判断；D1-C/D/E/F 只允许保留并安全停用；D1-G 按逐店逐月完整公式指纹判断；D1-H/I 原样保留；D1-J 完全排除。自动停用也属于处置，只有完整指纹、零直接/间接引用、零业务流水、零附件、零人工修改同时成立时才可执行；任一无法证明项保持现状并转人工复核。默认租户 1 永不自动删除、合并或重新归属。

品牌采用非破坏性 append-only 停用模型：为 `brand` 追加 `active` 状态且默认保持现有数据为启用；业务选择列表只返回启用品牌，历史记录按既有品牌 ID 回读时仍可显示停用品牌。不得删除或改名模拟停用，不得级联修改已有门店、商品或历史单据。

正常 Web 启动中的组织、财务 `@PostConstruct` 和员工 `ApplicationRunner` Seed 必须移除。STAGING/PRODUCTION 在业务写入前拒绝 Seed、旧账号 bootstrap 和 `APP_MIGRATION_AUTO_RUN` 危险开关；必须精确保留并回归 R1-02 已验证的 `--admin-bootstrap` 与 `APP_BOOTSTRAP_ADMIN_ENABLED=true` 一次性 CLI，不能笼统封禁全部 `APP_BOOTSTRAP_*`。

**验收门禁**

- 每一阶段先更新本策划书和证据目录；失败立即标记“阻塞”或“待系统验证”，不得进入下一阶段。
- 空 MySQL 8、V56 前向升级库、原始 Seed、存在引用、人工修改、部分缺失和 V43 投影不一致场景均有报告与负向断言；H2 只能做兼容回归，不能代替系统验收。
- 只有 D1-B/G 可在全部硬条件满足后进入处置候选；C/D/E/F 只停用不删除；H/I/J 零写入；租户 1 不被删除、合并或重新归属。
- 重跑、并发、审计失败和处置失败不产生部分状态；具备 dry-run、前镜像、事务锁、幂等、工单、执行/复核记录和回退或前向修复证据。
- STAGING/PRODUCTION 危险开关在任何业务写入前失败，同时正常 Web、R1-02 CLI、BOSS 登录/登出和 401/403 回归通过。
- 后端 clean test/package、Vue 生产构建、隔离 MySQL 8 空库/升级/真实 HTTP、JAR 与 Flyway 哈希、秘密扫描、端口保护和临时资源清理全部通过，才可把 R1-03 标为“已验证完成”。

**当前实施入口**

第 9.2 节是唯一现行、可复制执行的 R1-03 Codex 实施提示词。本节只保留冻结设计与验收摘要，避免出现两套相互漂移的实施指令。

### R1-04：生产启动安全守卫与 R1 系统验收

**设计思路**

补全运行时数据库身份守卫，在 Flyway 前校验环境、数据库名、端口、账号最小权限、MySQL 版本和 TLS；生产 CORS 只允许显式 HTTPS 域名。先固化正式库名和网关规则（D4），再测试失败路径。R1 最终验收不以“服务启动”代替，必须包含隔离 MySQL 空库/升级库、真实 HTTP 和安全日志验证。

**Codex 实施提示词**

```text
实施 R1-04：完成生产启动安全守卫并执行 R1 关卡验收。

前置条件：R1-03 已验证完成，D4 已确认。仅修改 R1 生产安全与验证代码；不开始库存、Token 哈希或多租户重构。

实现并调用真实 DatabaseRuntimeIdentityGuard：在 Flyway 前校验环境、MySQL 版本、端口、数据库名、当前账号、最小权限和 TLS。STAGING/PRODUCTION 遇到危险 Seed/bootstrap/migration 开关、空/弱初始化密码、非白名单 CORS、非 HTTPS 外部地址或不合格数据库身份必须失败。将 Nginx/应用 CORS 改为按部署环境显式 HTTPS 白名单，禁止公网 IP 任意端口通配。

执行 R1 完整门禁：后端 test/package，涉及配置或前端时执行 npm run build；MySQL 8 空库 Flyway、前向升级、启动正反例、HTTP 登录/401/403、审计日志和 QA 健康检查。所有测试必须在隔离环境；没有 QA 授权时保留“待系统验证”，不得标记 R1 完成或开始 R2。

把每项命令、Flyway 版本、环境边界、HTTP 结果、日志位置和未验证原因写入本策划书。全部通过后将 R1 标为“已验证完成”，才解锁 R2。
```

## 7. 后续工作包设计与 Codex 实施提示词（保持锁定）

### R2：库存原子扣减、发货状态机、幂等与对账

设计：在短事务中按稳定顺序锁定批次，使用 `quantity >= ?` 条件扣减并检查受影响行数；状态以 `expected status + version` 抢占，所有库存流水使用稳定业务幂等键；配送、流水、日志和 Outbox 同事务，待办由幂等消费者更新。真实 MySQL/InnoDB 并发扣减必须证明“不超卖、无重复流水、失败无副作用”。

```text
实施 R2，仅在 R1 已验证完成后开始。读取策划书，先冻结库存不变量和现有 Flyway 版本。实现批次行锁、条件扣减、非负约束、稳定业务事件幂等键、发货状态机与同事务 Outbox；不要直接写待办来源表。为同一商品并发发货、同单重复发货、死锁有限重试和库存账实对账编写真实 MySQL 8 测试。通过后端/前端构建、空库/升级迁移、HTTP 409 与隔离 QA 业务闭环后才更新 R2 为已验证完成；否则停止。
```

### R3：收货、退货、采购收货状态机与幂等

设计：收货、退货和采购收货分别以条件状态转换抢占；累计数量在锁内计算；单据与库存流水使用唯一幂等键；停用商品禁止新增入库但历史可读。

```text
实施 R3，仅在 R2 已验证完成后开始。为配送收货、退货回库、采购收货建立版本/状态条件更新、唯一约束和幂等键；所有库存增减、单据、日志和 Outbox 在同一事务。以真实 MySQL 并发验证同单只收一次、同单只回库一次、累计退货不超可退量、超收被拒绝、停用商品不能入库。完整验证通过并记录后才解锁 R4/R5。
```

### R4：报销、工资、利润、巡检并发控制

设计：为各聚合根加版本字段，编辑、审批和删除均携带预期状态与版本；冲突返回中文 409；导入使用明确冲突策略，不静默覆盖人工数据。

```text
实施 R4，仅在 R1 已验证完成后开始。一次只处理一个业务域，先定义状态机和唯一键，再新增兼容迁移、条件更新和中文 409 响应。真实 MySQL 并发测试必须证明批准/驳回只成功一个、同版本编辑不覆盖、工资和利润唯一规则成立、巡检识别不覆盖后续人工整改。每个子域完成系统验证并更新策划书后，才处理下一子域。
```

### R5：统一认证、授权和 Token 加固

设计：以 Spring Security 为默认拒绝入口，所有业务身份从认证上下文取得；登录改用 `tenantCode + username + password`，Token 数据库存哈希，停用/改密/登出吊销旧 Token；限流和锁定在多实例共享存储或网关层实现。运行时只认可 `BOSS` 等项目规范角色。

```text
实施 R5，仅在 R1 已验证完成后开始。加入 Spring Security 默认保护 /api/**，仅保留健康检查与正式登录匿名；Controller 不再手工解析 Authorization。以 tenantCode 登录，拒绝 tenantId 缺省/客户端伪造；Token 只存 SHA-256 哈希并实现轮换、绝对/空闲过期和吊销。为 401、403、跨门店、跨租户、附件越权、BOSS 角色和多实例限流写 MySQL/HTTP 测试。所有 API 契约、前端 401/403 处理和安全审计验证通过后才解锁 R6。
```

### R6：多租户数据库约束与数据审计

设计：先只读审计，再以 expand/migrate/validate/switch/contract 迁移复合唯一键和 `(tenant_id, foreign_id)` 外键；现有全局主键与是否引入 `store_pk` 必须先定稿，不可一次替换全部外键。

```text
实施 R6，仅在 R5 已验证完成且租户审计无阻断项后开始。先产出跨租户、空 tenant、孤儿记录、重复业务键的只读报告，不自动归属数据。随后分批新增复合唯一键/外键和 tenant 条件查询，保持旧版本兼容一个发布周期。真实 MySQL 必须拒绝覆盖范围内的跨租户关联，后台任务也须显式建立/清理 TenantContext。完成数据校验、回读兼容、401/403/404 策略测试后才解锁 R7。
```

### R7：停止 legacy KV 双写

设计：按业务 key 逐个退出；正式结构化表为唯一写入口，legacy 只能由正式表生成只读投影。每个 key 必须完成一个完整业务周期的数/金额/哈希对账。

```text
实施 R7，仅在相应业务模块已验证完成后开始。一次只关闭一个 legacy KV key：确认 Vue3/API 覆盖、停止写入、用结构化表提供只读投影、执行数量/金额/哈希校验、观察一个业务周期，再将 legacy 固化为 /legacy 只读。禁止把账号、密码或 Token 写入 KV。验证通过后更新该 key 状态，未通过不得关闭下一 key。
```

### R8：分布式任务、附件、外部服务与可观测性

设计：业务提交写 Outbox，消费者幂等；定时任务使用数据库锁或 ShedLock；慢任务进入持久化异步任务表。附件先做类型/大小/权限/审计，后续迁移对象存储；外部服务统一客户端、超时、熔断、限流、脱敏。最小监控不应等待 R8 才出现，R1/R2 已需具备关键告警。

```text
实施 R8，仅在 R5 已验证完成后开始。为每种任务定义租约、租约过期、幂等键、失败重试和死信处理；双实例测试同一任务只执行一次且可接管。附件上传/下载必须验证租户、门店、单据和上传者权限，拒绝伪装/超限文件并写日志。外部客户端复用连接、限制并发、熔断降级，AI 只能产生建议。接入结构化日志、Actuator/Micrometer 与死锁、库存差异、401/403、任务积压告警；演练通过后更新状态。
```

### R9：查询治理与 200 门店容量验收

设计：先消除 N+1 和固定截断，再设计以 `tenant_id` 为首的索引和游标分页；导入、导出、识别、AI 总结全部异步。容量结论必须基于规定数据集、机器配置、MySQL 配置、脚本版本和压测后的账实对账。

```text
实施 R9，仅在 P0-P2 已验证完成、慢任务异步化并有监控后开始。为高频列表建立分页/游标、批量明细加载和经 EXPLAIN ANALYZE 审核的 tenant-first 索引；不得用固定 limit 截断数据。构造 50 租户/200 门店/三年数据的测试租户，在受控环境执行规定并发负载和单实例故障接管。只有 P95/P99 达标、连接池无耗尽、库存/流水/配送/收退货对账为零且告警演练通过，才能标记 200 门店验收完成。
```

## 8. 每个工作包的完成记录模板

实施者在解决每个问题后，必须将以下模板复制到对应章节；任何空项都不能标记“已验证完成”。

```text
### <工作包编号> 完成记录
- 状态：待系统验证 / 已验证完成 / 阻塞
- 代码与迁移：<文件、Flyway 版本、接口契约>
- 业务不变量：<本次证明的规则>
- 本地验证：<命令、退出码、日期>
- MySQL 8 空库与升级验证：<库名仅写脱敏标识、Flyway 版本、结果>
- HTTP/系统验证：<用例、401/403/409、请求 ID、日志位置>
- 数据对账：<库存/流水/金额/幂等结果>
- 前端验证：<构建、桌面/移动端结果；如不涉及则说明>
- 证据目录：output/release-evidence/<发布包>/...
- 阻塞或残余风险：<无则写无>
- 放行结论：<是否解锁下一包，以及依据>
```

## 9. 当前下一步

R1-02 已验证完成。D1/D2 已于 2026-07-16 通过 V2 重新签署关闭：`output/release-evidence/R1-03-decision-freeze-20260715/d1-d2-signed-decision-v2.md`，SHA-256 为 `88483578A2D7A99756987ACF69E126EBFAD312441988FC2CA5CD71413FAB2AD3`。旧 `d1-d2-signed-decision.md` 仅作为错误时间审计历史。**R1-03 当前状态为“待开发”**；允许按已冻结设计开始 R1-03，R1-04 和 R2～R9 继续锁定。

### 9.1 下一步：按冻结设计实施 R1-03

- 证据门禁：只认可决策草案、`signoff-confirmation-source.md` 和 V2 的真实哈希与单调时间链；旧签署文件不能作为放行依据。
- Flyway：只能追加迁移；开始时重新确认 MySQL/H2 最新版本和哈希，绝不改写 V1～V56，也不预设下一版本一定是 V57。
- 分类边界：A 只认定谱系；B 批次和 V43 投影整体判断；C/D/E/F 保留并安全停用；G 逐店逐月判断；H/I 原样保留；J 排除于自动处置。
- 安全硬条件：任何自动清理或停用都必须同时满足完整指纹、零直接/间接引用、零库存/采购/配送/退货/财务流水、零附件、零审计或人工修改证据；无法证明即保持原状并转人工复核。
- 品牌停用：以追加 `brand.active` 的非破坏性模型实施；业务选择列表隐藏停用品牌，历史关联仍可回读，禁止删除、改名或级联改写历史数据。
- 运行时写入：移除组织、财务 `@PostConstruct` 和员工 `ApplicationRunner` Seed；显式开发夹具必须隔离且生产不可达。
- 安全执行：先只读扫描和 dry-run，再以事务、锁、幂等、前镜像、工单、执行/复核记录和操作审计执行获准动作；保留期 90 天，恢复目标 RTO 4 小时、RPO 1 小时。
- 环境守卫：STAGING/PRODUCTION 必须在任何业务写入前拒绝 Seed、旧账号 bootstrap 和 `APP_MIGRATION_AUTO_RUN`，同时保留并回归 R1-02 的精确首 BOSS CLI。
- 验收边界：H2/Mock 不是系统验收；发布候选只能从正式 `backend` 构建，不能包含 `output/**` 中的历史源码快照。

### 9.2 Codex 实施提示词：R1-03 分阶段实施与系统验收

```text
实施 AI Profit OS 的 R1-03：历史 Seed 审计、最终状态治理与生产 Seed 阻断。

工作目录：C:\Users\34706\Documents\Codex\2026-07-10\new-chat\outputs\AI-Profit-OS-Qoder-Source-20260710-112707

一、强制前置门禁

1. 完整读取：
   - AGENTS.md
   - docs/backend-risk-remediation-execution-plan.md
   - C:\Users\34706\Documents\xwechat_files\wxid_zgw781jjm8h522_0eed\msg\file\2026-07\backend-risk-remediation-plan.md
   - output/release-evidence/R1-03-decision-freeze-20260715/seed-audit-decision-draft.md
   - output/release-evidence/R1-03-decision-freeze-20260715/signoff-confirmation-source.md
   - output/release-evidence/R1-03-decision-freeze-20260715/d1-d2-signed-decision-v2.md
   - output/release-evidence/R1-02-20260715/r1-02-isolated-mysql-20260715-231058-31a13b62.json
2. 复算并核对：
   - 草案 SHA-256 = 8DA4B325D95972005DA5190A32E857AE11CDD255951986FDD018F00FE65A1EC4
   - 确认来源 SHA-256 = 2D2394F0EF604888C4A54E0F42713010BBFFA59CD4C30B858F939E98B285EB03
   - V2 SHA-256 = 88483578A2D7A99756987ACF69E126EBFAD312441988FC2CA5CD71413FAB2AD3
   - 时间链必须为“草案定稿 < 重新确认取证 < 来源记录定稿 < V2 定稿”。
3. 确认 R1-02 为“已验证完成”、D1/D2 V2 有效、R1-03 为“待开发”。任一哈希、时间链或状态不匹配，立即停止，不修改产品代码，把 R1-03 标记为阻塞并报告。
4. 记录 `git status --short --untracked-files=all`、当前分支/HEAD、所有脏路径和产品范围哈希。当前工作区有用户未提交修改，必须逐项保留；禁止回滚、覆盖、提交或推送。
5. 重新盘点 MySQL/H2 迁移版本和哈希。只能新增 append-only 迁移，绝不修改 V1～V56，不能预设下一版本是 V57。
6. 禁止连接、写入、停止或复用 3306、3307、生产数据库及现有服务。系统验收只能使用随机端口、随机临时数据目录和非 root 隔离 MySQL 8。
7. 建立新的 R1-03 证据目录，秘密、密码、Token、数据库凭据和连接串不得写入日志、命令行或证据。

二、阶段 1：实现前契约复核

1. 逐行确认 V3/V4/V7/V8/V10/V43、组织/财务/员工 Seed、Repository、外键、流水、附件、审计和配置绑定的真实结构。
2. 将已冻结的品牌方案落到技术契约：只通过新迁移追加 `brand.active`，默认现有品牌启用；业务选择列表过滤停用品牌；历史关联查询仍能显示停用品牌；禁止删除、改名或级联重写。
3. 冻结候选状态、dry-run、前镜像、审批/执行/复核、审计、幂等、回滚/前向修复的数据结构和事务边界。
4. 如果真实 schema 与上述冻结设计冲突，先停止并把具体冲突写回策划书，不得静默改换设计。
5. 本阶段复核通过并更新策划书后，才能进入阶段 2。

三、阶段 2：移除正常启动的运行时 Seed

1. 移除组织、财务 `@PostConstruct` 和员工 `ApplicationRunner` 的业务数据写入能力；正常 Web 启动、HTTP 请求和普通业务 Service 均不得触发 Seed。
2. 如确需开发夹具，只能是显式调用、隔离测试/开发环境专用且 STAGING/PRODUCTION 不可达的工具；不得重新引入 Spring Bean Runner、Web 管理接口或默认开启开关。
3. 增加架构和单元测试，证明正常 Spring Context 中不存在上述自动写入路径，空库启动不会由运行时代码新增组织、财务、员工或账号数据。
4. 先执行定向测试并记录结果；失败立即停止，不进入阶段 3。

四、阶段 3：追加治理结构和只读扫描

1. 在重新确认的下一个可用版本新增 MySQL append-only 迁移；同步提供必要的 H2 兼容迁移和 CI 最新版本门禁，但 H2 不能作为系统验收。
2. 建立 Seed 治理/审计结构，至少记录：稳定对象键、原始指纹、谱系、扫描时间、直接/间接引用计数、业务流水、附件、审计/人工修改信号、建议动作、审批状态、前镜像摘要、工单、执行人、复核人、处置结果和错误摘要。
3. 先实现完全只读的扫描与 dry-run。扫描覆盖 V3 默认租户、V4 五个物料及 `-SEED` 批次、V43 库存投影、V7 默认供应商、品牌/门店 fallback、demo fallback 利润和三条运行时 Seed 谱系。
4. 默认租户 1 永不自动删除、合并或重新归属。任一指纹、引用、流水、附件或人工修改信号无法证明为零时，状态必须是“保持原状/人工复核”。
5. 对空库完整迁移、V56 升级、原始 Seed、人工修改、存在引用、部分缺失、V43 投影不一致分别验证扫描结果。通过后更新策划书，再进入阶段 4。

五、阶段 4：严格执行 D1 A～J 与 D2=A

1. A 只记录 V8/V10/V43 到 V4 的确定性谱系，不自动修改数据。
2. B 的五个 `-SEED` 批次与 V43 投影必须整体、原子判断；禁止只处理批次或只处理投影。只有全部硬条件成立并有批准记录才可执行获准动作。
3. C/D/E/F 只允许保留并安全停用，禁止物理删除。自动停用也必须满足完整指纹、零直接/间接引用、零流水、零附件、零人工修改；不满足时保持当前状态并转人工复核。
4. G 必须逐店逐月验证完整公式指纹和零下游证据，禁止按租户批量猜测。
5. H/I 按真实业务/人事数据原样保留；J 必须从自动处置扫描执行器中排除，断言零写入。
6. D2=A 只要求最终状态不存在可用演示数据；不得改写 V1～V56，不得把候选资格当成生产删除授权。
7. 所有获准动作必须支持 dry-run、事务锁、幂等重跑、前镜像、工单、执行/复核人、操作审计、失败整体回滚或可验证的前向修复。备份保留 90 天，恢复演练目标为 RTO 4 小时、RPO 1 小时。
8. 为并发执行、重复执行、审计失败、锁超时和中途异常编写真实 MySQL 8 测试，证明无部分处置。通过并更新策划书后才进入阶段 5。

六、阶段 5：生产 Seed 阻断与兼容回归

1. STAGING/PRODUCTION 在任何业务数据写入前拒绝 `APP_SEED_*`、旧默认账号/店长账号 bootstrap 开关及 `APP_MIGRATION_AUTO_RUN` 等危险配置；同步在 Seed 实现自身 fail closed。
2. 不得笼统禁止全部 `APP_BOOTSTRAP_*`。必须保留 R1-02 的精确 `--admin-bootstrap` + `APP_BOOTSTRAP_ADMIN_ENABLED=true` 非 Web CLI，并验证普通 Web 路径不能触发它。
3. 清理活动配置、CI、启动/验证脚本和部署文档中的失效 Seed 入口；发布只能从正式 `backend` 构建，`output/**` 历史快照不得进入候选 JAR 或发布清单。
4. 回归正常 Web 启动、R1-02 双 CLI/重复执行、BOSS 登录/me/users/logout、登出 Token 复用 401、无 Token 401 和越权 403。失败立即停止。

七、阶段 6：完整本地与系统门禁

1. 执行：
   - `cd backend; mvn -q clean test`
   - `cd backend; mvn -q -DskipTests package`
   - `cd frontend-vue; npm run build`
2. 记录测试数、退出码、候选 JAR SHA-256、Flyway 最新版本、V1～V56 哈希不变证明和新增迁移哈希。
3. 使用自建、随机端口的隔离 MySQL 8 完成：
   - 全新空库完整迁移后的最终状态；
   - V56 到最新版本的前向升级；
   - 原始 Seed、引用存在、人工修改、部分缺失、投影不一致场景；
   - 并发、重跑、失败回滚、审计和备份恢复演练；
   - 真实 HTTP 健康检查、认证、401/403 与关键查询。
4. 最终断言：无默认账号、无可用演示库存；真实/历史/未知来源数据没有被自动删除或错误停用；B 与投影一致；C/D/E/F 只发生获准停用；H/I/J 零写入；治理和操作审计完整。
5. 扫描源码差异、JAR、日志和证据，确认无密码、Token、Hash、数据库秘密、带凭据 URI 或私钥。确认 3306、3307、18081 及其他既有进程前后不变，停止自有 Java/MySQL 并删除临时目录。
6. 禁止使用 H2、Mock、单元测试或“服务能启动”冒充上述 MySQL/HTTP 验收。

八、状态和交付规则

1. 每个阶段通过后，立即把设计、文件、命令、退出码、计数和证据路径写入本策划书；未记录视为未通过。
2. 任一阶段失败：立即停止后续阶段；按事实标记“阻塞”或“待系统验证”，保留失败证据，不得重跑掩盖首个失败。
3. 代码与本地构建通过但隔离 MySQL/HTTP 未完成时，只能标记“待系统验证”。
4. 只有阶段 1～6 全部真实 PASS，才能把 R1-03 标记“已验证完成”。R1-04 仍需 D4 关闭，不能自动解锁；不得开始 R2～R9。
5. 不提交、不推送、不回滚用户文件。最终报告必须列出修改文件、迁移版本、测试/构建、MySQL/HTTP 结果、数据对账、证据目录、服务地址、残余风险和明确放行结论。
```

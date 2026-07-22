# 受限内部试用上线前最小检查与发布准备

- 检查时间：2026-07-22 10:07～10:58（Asia/Shanghai）
- 产品结论：**RESTRICTED INTERNAL PILOT / RISK ACCEPTED**
- 候选冻结结论：**RELEASE CANDIDATE READY FOR HUMAN REVIEW**
- 本轮范围：仅本机 QA 上线前检查、候选制品构建、备份与恢复演练；未部署任何生产或内部目标环境。
- 完整验收状态：FLOW-07 深度回归、G5、G6、G7 均为 **POST-RELEASE PENDING**，不是 PASS。
- 部署判定：**NOT AUTHORIZED**。本轮仅到提交前人工复核，不提交、不打标签、不推送、不部署；目标环境、账号白名单与上一版制品等部署停止条件仍未解除。

## 1. 候选身份与工作区审计

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Git 分支 / HEAD | 已识别 | `main` / `a7a4288931b2212bb25e6199fed25cc839712e74` |
| 工作区 | READY FOR HUMAN REVIEW | 262 个实际暂存差异（A87/M157/D18）；93 个未暂存差异均在批准排除范围，未跟踪文件 0。候选运行源码不存在未暂存差异。 |
| 后端 JAR | 已生成 | `store-profit-backend-0.1.0-SNAPSHOT.jar`，SHA-256 `82409e084732d90d94ce12d578c24323ca23ce15c4008e446cdae222d8c37d4c` |
| 前端 dist 树 | 已生成 | 按相对路径排序后的逐文件 SHA-256 再汇总：`78ed4881ce2dac7a01c1df2de3266b8d6971f4b7ec074f7084dbabe87f805742` |
| 无关文件控制 | PASS（候选边界） | 使用逐文件显式路径暂存；68 项历史文档删除、运维/compose/.github、移动端、脚本和运行产物均未纳入。未删除、回滚或覆盖用户修改。 |

本轮只使用显式路径执行 `git add`；没有执行 `git add .`、`git add -A`、`commit`、`push`、`reset`、`checkout`、`clean`、打标签或清理。当前仍是提交前暂存候选，尚未形成可由提交复现的冻结版本。

## 2. 构建与核心安全回归

| 检查 | 结果 | 摘要 |
| --- | --- | --- |
| 后端定向核心测试 | PASS | 13 套件，101/101，通过；0 failure、0 error、0 skip。覆盖身份、角色权限、数据范围、令牌权限版本、数据库身份、Flyway 候选链、健康检查、巡检受控 YOLO、整改流程及 QMAI-04 关闭。 |
| Maven package | PASS | `mvn -q package` 退出码 0；按 Maven 生命周期再次执行测试。 |
| Vue 类型检查 | PASS | `vue-tsc -b` 退出码 0。 |
| Vue 生产构建 | PASS | Vite 8.1.5，1988 modules transformed，退出码 0。 |
| 后端全量测试 | PASS | 本轮 `mvn -q test`：183 套件、887/887，0 failure、0 error、0 skip；退出码 0。 |

## 3. 本机 QA 运行检查

只使用 Docker 本机 QA MySQL、`qa` profile 与 `127.0.0.1` 候选服务。未启动前端、未访问公网/局域网、真实 YOLO、真实企迈、生产或真实业务数据。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 网络隔离 | PASS | 后端仅监听 `127.0.0.1:18181`；MySQL 仅发布到 `127.0.0.1:13307`。 |
| 健康检查 | PASS | `GET /api/health` 为 200/UP；错误日志 ERROR 计数为 0。 |
| QA 身份 / 迁移 | PASS WITH OBSERVATION | 诊断为 `environment=QA`、数据库 `ai_profit_os_qa`、Flyway V80 且无需迁移。数据库账号诊断为 `UNRESTRICTED_OR_TEST`；本机容器靠 loopback 隔离，目标环境必须换为主机范围受限的最小权限账号。 |
| 登录 / 正式角色 | PASS | 合成 BOSS、FINANCE、STORE_MANAGER、SUPERVISOR、EMPLOYEE、WAREHOUSE 均登录 200，返回角色与 tenant=1 一致；未输出 token 或密码。 |
| 401 / 403 | PASS | YOLO 匿名为 401、EMPLOYEE 为 403；店长访问他店巡检数据为 403。 |
| 租户隔离 | PASS（核心测试） / QA 深度项延期 | 定向 H2/HTTP 测试覆盖跨租户拒绝；当前本机 QA 只含 tenant=1 合成账号，未伪造第二租户真实 QA 演练。此项纳入 G5/G6 深度回归。 |
| QMAI-04 | CLOSED | 后端默认 `QMAI_RECIPE_ENABLED=false`，BOSS 直调配方接口返回 503 / `QMAI_RECIPE_DISABLED`；前端生产构建默认隐藏入口。企迈出站模式为 `DISABLED`。 |
| YOLO | PASS（受限关闭态） | QA 地址固定为 `127.0.0.1`，出站模式 `DISABLED`；授权调用返回 503，`inspection_record` 为 1→1，零自动扣分、零错误业务写入。文件名在普通日志和操作审计中的命中均为 0。 |

YOLO 若后续在内部目标开启，只能使用工程负责人批准的公司内部受控地址，并保持服务端固定地址、禁重定向、超时/5xx/非法响应零写入。公网、请求参数指定地址及真实第三方 YOLO 均禁止。

## 4. 数据库备份与回滚验证

- 备份文件：`/Users/a1/.local/qa-internal-pilot/backups/restricted-pilot-preflight-20260722-1012.sql`
- 权限：目录 700，文件 600。
- SHA-256：`efba13533f712f3f74e5ff4e68c4124cf2392ef5b7789bda22bb6c07430d0d54`
- 大小：599,908 字节。
- 恢复演练：备份恢复到一次性 `ai_profit_os_qa_restorecheck_20260722`；源库与恢复库均为 109 张表、Flyway V80，结果 PASS；验证后临时恢复库已删除，原 QA 库未覆盖。

明确回滚顺序：

1. 关闭内部入口并停用本次试用账号，使现有会话失效；确认 401/403 与审计仍可查。
2. 停止新候选服务；切回发布前冻结的不可变 JAR 与前端 dist，并保持 QMAI-04 及所有外呼关闭。
3. 仅当迁移/数据写入确认需要恢复且数据库负责人批准时，先核对备份 SHA-256，再恢复到新库做表数、Flyway 与关键行数比对，最后通过受控切换启用；禁止直接覆盖当前库或以整库恢复代替普通版本回退。
4. 复核 `/api/health`、受保护 diagnostics、登录、401/403、跨范围拒绝、Flyway 版本和 ERROR 日志；异常即继续停服。

数据库恢复路径已真实演练；应用版本回滚仍缺“发布前不可变上一版制品”，因此部署前必须补齐上一版 JAR/dist 的路径与 SHA-256。没有该制品不得开始部署。

## 5. 账号、开放范围与观察点

- 本机 QA 当前有 9 个启用的 `e2e_g4_*` 合成账号，仅用于验证，不代表正式内部试用白名单。
- 产品/数据范围负责人尚未提供目标环境的“账号、租户、门店、角色、启停时间”书面清单；未取得清单前不得开放任何账号。
- 目标服务必须绑定内网地址并由公司身份网关/VPN 或等价访问控制保护；不得公开监听或配置公网入口。
- 健康观察：匿名 `/api/health` 只暴露 UP；`/api/health/diagnostics` 仅 BOSS/system dashboard 权限可读。
- 日志观察：启动失败、Flyway validate/migrate、连续 5xx、YOLO 超时/5xx/非法响应、QMAI-04 调用尝试、登录失败、401/403、`permission_denied`、账号/范围变更。
- 立即回滚条件：权限绕过、跨租户/跨店读取、原图非受控出站、QMAI-04 可访问、迁移失败、健康持续 DOWN、错误业务写入、无法停用账号/会话或无法核验备份。

## 6. 延期测试与发布前停止条件

以下均不得写为 PASS：

- FLOW-07 深度回归：**POST-RELEASE PENDING**。
- G5 外部能力、文件与故障：**POST-RELEASE PENDING**。
- G6 安全、并发、恢复与性能：**POST-RELEASE PENDING**。
- G7 复核与签署：**POST-RELEASE PENDING**。
- QMAI-04：关闭/延期，不是 PASS。

部署前必须全部补齐：

1. 将当前 159 修改/新增、68 删除、39 未跟踪项归并为经复核的干净候选提交/标签，并从干净检出重建制品。
2. 提供明确的内部目标环境、部署方式、网络边界和授权；本轮授权不包含部署。
3. 提供最小账号白名单及租户/门店范围，由产品、数据范围与安全负责人签字。
4. 提供上一版不可变 JAR/dist 及 SHA-256，完成应用切回演练或受控演练记录。
5. 目标数据库账号必须是非 root、最小权限、主机范围受限；启动 diagnostics 不得显示 `UNRESTRICTED_OR_TEST`。

在上述停止条件解除前，结论保持：**RESTRICTED INTERNAL PILOT / RISK ACCEPTED（产品接受延期测试风险）**，但**不得执行部署或宣称完整验收 PASS**。

## 7. 候选冻结审计（2026-07-22）

本节只记录只读审计结果。索引为空；没有执行 `git add`、提交、标签、worktree、重构建或推送。

### 7.1 精确工作区状态

- 基准提交：`a7a4288931b2212bb25e6199fed25cc839712e74`。
- 已跟踪修改/新增：159 个文件。
- 已跟踪删除：68 个文件。
- 未跟踪：51 个实际文件。此前“39”是 Git 折叠未跟踪目录后的状态条目数，不能作为候选文件数。
- 已跟踪差异合计：227 个文件，约 `+4006/-12871`。
- 暂存区：空。

### 7.2 候选分类

**A. 可能属于内部试用运行闭包，但尚未获准暂存**

- 后端运行代码与配置：`backend/src/main/java/com/storeprofit/system/{assistant,config,employee,employeeassistant,finance,health,importing,inspection,knowledgebase,operations,organization,platform,qmai,reporting,salary,todo,warehouse}/`，以及 `application.yml`、`application-qa.yml`。
- 数据库迁移：11 个未跟踪 SQL，包括 MySQL V76～V80、H2 V74～V78 和一个 H2 V24 兼容迁移；另有 migration-h2 README 修改。当前代码、QA V80 和测试互相耦合，禁止只挑选部分迁移。
- 前端运行代码：巡检、助手、员工助手、日报损、门店管理、仓库、平台配置、待办导航、权限菜单与路由相关文件；其中知识库 API/页面和待办导航仍是未跟踪文件。
- 直接及回归测试：后端与前端共包含跨角色权限、员工审计、财务导入/导出、仓库、巡检/YOLO、助手/知识库、QMAI、门店和待办导航等多个批次；部分测试为未跟踪文件。

以上不是批准后的候选白名单。它们横跨 R1、G3、G4 和后续修复，缺少逐文件需求/缺陷编号映射，无法由本轮审计可靠判断全部都应进入同一个发布提交。

**B. 验收报告和发布证据**

- 已跟踪修改：`g3-gate-ledger.md`、`defects-and-waivers.md`、`test-ledger.md`。
- 当前发布清单、FLOW 报告及大量 G0～G4 报告位于被 `.gitignore` 排除的 `output/` 下。是否使用 `git add -f` 纳入证据提交必须由发布负责人明确决定；本轮未强制暂存。

**C. 明确不应夹带到本次运行候选，或来源需单独确认**

- 68 个删除项：主要为旧设计、移动端、部署、历史审计和发布文档清理，另含 `PACKAGE_MANIFEST.sha256`、`QODER_HANDOFF.md`。它们不影响当前运行闭包，应从候选提交排除，除非文档负责人明确批准单独提交。
- 13 个 `scripts/` 修改：包括云演示账号、移动预发布、助手运行账号轮换与 QA fixture；与本轮桌面受限试用候选冻结不是同一发布动作。
- `.github/`、`deploy/docker-compose.qa.yml`、`docker-compose.dev.next.yml`：CI/部署配置来源跨批次，必须由 DevOps 逐项确认。
- 知识库完整模块、QMAI 配方模块及相关迁移虽然已成为当前源码依赖，但其业务范围超出“QMAI-04 关闭”的最小发布说明，必须由后端负责人明确选择“纳入并保持关闭”或“连同依赖安全拆出”。

**D. 敏感信息、运行产物与临时文件**

- 高置信度扫描未发现私钥、AWS/GitHub/OpenAI 格式密钥；未发现候选变更中的 JAR、dist、日志、备份、图片、PDF、Excel 或数据库 dump。
- `backend/src/main/java/com/storeprofit/system/employee/EmployeeService.java` 含固定共享初始密码逻辑。值未记录；该风险与“不得使用固定默认密码”的项目规则冲突，安全负责人必须在冻结前决定改为随机一次性凭据或书面接受仅内部受控的临时方案。
- `MobileApiContractTest.java` 与 `AdminBootstrapCommandTest.java` 含测试 token/password 字面量；扫描未把它们认定为真实凭据，但需测试负责人确认均为合成 fixture。
- `.env.qa` 与 `frontend-vue/.env.local` 被忽略，禁止加入候选或输出内容；后者本机权限为 666，应由文件所有者在发布流程外收紧。
- 被忽略的 `g4-qa-synthetic-baseline.sql`、验收截图、上传样本 PNG、JSON 汇总及本机 QA 日志/备份均不得使用强制暂存加入代码候选。
- 外部 URL 出现在助手、QMAI、应用配置、CI、测试和 QA 脚本中。现有受限试用要求出站默认关闭；DevOps/安全负责人仍需对最终白名单逐文件复核固定主机与出站守卫。

### 7.3 最小人工确认清单

1. 产品/工程负责人给出逐模块选择：助手/知识库、员工、财务、巡检、仓库、门店、待办、QMAI、权限基础分别纳入或排除，并关联需求/缺陷和验收报告。
2. 数据库负责人批准完整的 MySQL V76～V80 与对应 H2 链，确认未跟踪迁移均属于当前候选且没有已执行迁移被改写。
3. 安全负责人处理或书面裁决固定员工初始密码，并复核所有外部 URL、账号模板和出站默认关闭配置。
4. 前端负责人确认知识库、QMAI-04 隐藏、仓库/门店/巡检页面及相关路由是否全部属于本候选。
5. QA 负责人确定证据提交边界：仅三个已跟踪台账，还是明确批准强制加入指定的被忽略报告；SQL、截图、上传样本、日志和备份必须排除。
6. 文档负责人确认 68 个删除项另行提交或保留在工作区，本次运行候选不得夹带。
7. DevOps 确认 `.github`、compose、deploy 与 13 个脚本的逐文件去留；不得整体加入。

本节记录的是发布负责人授权前的历史阻断状态，已由第 8 节最新审计取代。FLOW-07 深度回归及 G5～G7 仍保持 **POST-RELEASE PENDING**。

## 8. 发布负责人授权后的提交前准备

- 授权时间：2026-07-22（Asia/Shanghai）。
- 授权范围：backend、frontend-vue 的受限内部试用运行源码、对应测试、原 11 项相互依赖 Flyway 迁移，加首次强制改密的 MySQL V81/H2 V79 两项迁移，以及 36 个纯 Markdown 验收报告，作为不可拆分候选整体。
- 员工首次密码：固定共享密码已移除；每个账号生成独立 20 位强随机初始密码，仅在开号成功响应显示一次，数据库只保存 PBKDF2 哈希并原子设置 `password_change_required=true`。首次登录只签发受限改密凭据；改密成功后状态清除、令牌版本递增、初始密码失效，审计不保存密码。
- 本机 `frontend-vue/.env.local`：只收紧权限为 600；仍被忽略，内容未读取、未输出、未暂存。
- 首次改密直接及历史迁移回归已纳入全量：覆盖独立初始密码、PBKDF2 与强制改密状态、受限凭据、弱/不一致/过期凭据零写入、改密后初始密码失效和旧令牌失效、脱敏审计及事务回滚；`AuthTokenHashMigrationTest` 历史夹具与 V81 上限同步通过。
- 后端全量回归：183 套件，887/887 通过；0 failure、0 error、0 skip；退出码 0。
- 后端 package：`mvn -q package` 退出码 0；当前工作区 JAR SHA-256 为 `82409e084732d90d94ce12d578c24323ca23ce15c4008e446cdae222d8c37d4c`。
- 前端 `vue-tsc -b`：退出码 0。
- Vite 8.1.5 生产构建：1988 modules transformed，退出码 0；dist 确定性汇总 SHA-256 为 `78ed4881ce2dac7a01c1df2de3266b8d6971f4b7ec074f7084dbabe87f805742`。
- 工具版本：Java 21.0.11、Maven 3.9.16、Node 24.14.0、npm 11.9.0、vue-tsc 5.9.3、Vite 8.1.5。
- 验收报告复扫：36 个 Markdown 文件未发现高置信度私钥/API 密钥、凭据字面量或数据库连接串。
- 精确暂存：264 个批准路径中有 262 个实际差异；另外两份报告与 HEAD 相同。暂存状态 A87/M157/D18，约 `+11429/-2647`，白名单外 0；剩余 93 个未暂存差异全部属于批准排除项，未跟踪文件 0，候选运行源码没有未暂存差异。
- 候选载荷补丁 SHA-256：`01e087b2e5a18c5169f86b36c3d32eeeef7363893a18f43e2159a37bb644d3bc`。该稳定哈希明确排除本 readiness 与暂存清单两份自描述证据文件；包含证据文档的最终完整暂存补丁哈希在提交前报告给出。
- `git diff --cached --check`：退出码 0。
- 完整路径清单：见 `release-candidate-staging-manifest.md`；提交与候选标签仍未创建，等待发布负责人复核。

明确排除：68 项历史删除、`.env*`、构建产物、SQL QA 基线、日志、备份、截图、上传样本、JSON 运行摘要、`.github`、compose、deploy、云演示、移动端、账号轮换、QA/验证脚本。上述排除项继续留在工作区，不删除、不回滚、不暂存。

本节仍是提交前状态。未创建提交、标签或干净 worktree；当前结论为 **RELEASE CANDIDATE READY FOR HUMAN REVIEW**。这只表示候选暂存边界和本机构建门禁已就绪，不构成部署授权，也不表示提交后干净重构建已完成。FLOW-07 深度回归及 G5～G7 继续为 **POST-RELEASE PENDING**。

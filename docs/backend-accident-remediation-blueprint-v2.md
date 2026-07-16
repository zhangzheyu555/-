# AI Profit OS 后端事故整改蓝图 v2

状态：BR2-00 已完成远程基线冻结；当前发布就绪性为阻断。
远程事实基线：codex/deepseek-assistant @ 3d37ef6b3cc37f70fad9c328a360dd6aab97f7a1，tree 2b872e9f7076cef5c0d58c3b9b49c9a9ec0500a6。
证据包：output/release-evidence/BR2-00-20260716-105934/。

## 1. 适用边界与事实层

本蓝图仅定义 BR2-A 至 BR2-D 的整改顺序、事故链、不可变量和验收门禁；不实施代码、不创建迁移、不把本地未提交修改当作远程交付。

事实必须按以下层次使用：

1. 远程源码层：本蓝图的现状结论均来自冻结的 remote HEAD。
2. 本地覆盖层：当前工作区的 R1、V56/V57 与其他修改仅为候选参考，未合入/未部署状态未知。
3. 部署层：commit、JAR、Flyway、账户/授权、APP_ENV、TLS、租户数据均未知；不得通过连生产或 3307 推断。
4. 隔离验证层：本次只使用随机临时 MySQL 8.0.46，已清理。

不变量：

- 生产 Web 启动不得创建固定账号、默认密码、示例门店、示例库存、示例财务或遗留导入数据。
- BOSS 是唯一当前最高角色；ADMIN/OWNER 仅能作为历史迁移输入。
- 所有外部入口必须认证、授权、租户/数据范围检查；匿名入口必须显式登记。
- 库存、单据、审批、导入的状态变更必须具备单一原子事实来源、可解释冲突语义和审计。
- 已执行的 V1-V55 不得改写。任何数据库结构/数据修复均为 append-only，并先有空库、升级库、脏数据/并发验收。
- 无法证明安全的历史 seed/数据一律保留，不自动删除、合并或重新归属租户 1。

## 2. 基线结论

- MySQL migration 已 V55，但两个 CI 脚本仍锁死 V54：.github/scripts/verify-mysql-flyway.sh:5、.github/scripts/verify-release-source.sh:7。
- 隔离 MySQL 8.0.46 空库实际在 V40 第 263 行失败，错误 1267：utf8mb4_unicode_ci 与 utf8mb4_0900_ai_ci 混用。最新成功 V39、失败 V40。
- 因服务不能通过迁移启动，BR2-B/C/D 动态 HTTP/并发复现均被有效阻断；不可伪造为通过。
- 远程源还暴露启动 seed/bootstrap、身份守卫 no-op、默认 tenant/demo seed、库存/审批并发及会话/健康/导出硬化缺口。
- 新版执行依据是本蓝图和执行计划 v2；外部旧方案只保留为历史输入，不能再承载当前完成状态。

## 3. BR2-A：启动、迁移与发布恢复

### A-01 P0：MySQL 8 空库 V40 迁移阻断

链路：

StoreProfitApplication.main:11-15 → DatabaseRuntimeGuardConfiguration:9-16 → Flyway → V40__correct_rugua_quality_standard_and_repair_audit.sql:263 → MySQL schema history。

- 表/不变量：flyway_schema_history；在支持的 MySQL 8.0.46 空库和升级库中必须完整迁移且失败记录为 0。
- 当前保护：application.yml:33-42 启用 Flyway、validate-on-migrate，禁 clean/out-of-order。
- 当前缺口：V40 的 collation 比较不能适配默认 MySQL 8.0.46 0900_ai_ci。
- 已完成复现：见证据包 reproduction-results.md；未修改 V40 来绕过。
- 最小修复方向：追加迁移或受控兼容方案，保留 V40 历史校验和；不能改写 V1-V55。
- 验收：全新 MySQL 8.0.46、V39 升级数据库、带历史巡检记录数据库、不同批准排序规则均迁移到最新版本；失败迁移为 0。

### A-02 P0：运行时数据库身份守卫是 no-op

链路：

StoreProfitApplication:11-15 → DatabaseEnvironmentGuard.validate → DatabaseRuntimeGuardConfiguration:9-16 → DatabaseRuntimeIdentityGuard.verifyBeforeMigrationOrStartup:17-19 → Flyway。

- 表/不变量：仅批准的 MySQL 8、批准库、受限非 root 运行账号可进行启动/迁移；不得有 global privilege 或 GRANT OPTION。
- 当前保护：DatabaseEnvironmentGuard:46-58 仅检查 JDBC URL 和用户名文本；validateIdentity:26-74 有纯校验规则。
- 当前缺口：DatabaseRuntimeIdentityGuard.run:12-15 和 verify:17-19 为空，不访问 DataSource，因此真实 current_user、SHOW GRANTS、实际 database/port 从未验证。
- 最小复现：外观符合 STAGING 字符串的账号实际持有 *.* 或 GRANT OPTION，仍会进入 Flyway。
- 验收：迁移前读取 version、@@port、database、current_user、SHOW GRANTS 并执行校验；root、全局授权、错误库/端口、无 TLS/错误 TLS 均 fail closed。

### A-03 P0：CI Flyway 版本门禁确定失效

链路：

GitHub workflow source-hygiene → verify-release-source.sh:55-85、257-258；MySQL smoke → verify-mysql-flyway.sh:72-86 → flyway_schema_history。

- 当前保护：脚本确实检查成功/失败迁移和最新版本。
- 当前缺口：源码 MySQL/H2 已有 V55，脚本常量仍为 V54；当前 remote HEAD 确定失败。脚本也不验证连续序列和 H2 语义差异。
- 已完成复现：Git for Windows Bash 执行发布源脚本报两端 V55；MySQL gate 在非 CI 环境按脚本要求缺 APP_ENV 等变量，且本机无 Docker 不能复用 docker exec 路径。
- 最小修复方向：将“期望最新版本”计算/单源化，并新增序列、唯一性、MySQL/H2 allow-list 和真实 MySQL smoke。
- 验收：同一最新版本规则在 CI、脚本、空库、升级库一致；不得只改常量后忽略 V40。

### A-04 P1：生产启动可写业务账号和演示数据

链路：

application.yml:51-61 环境开关 → AuthService @PostConstruct:124-145、OrganizationSeedService:36-98、FinanceSeedService:47-146、StoreManagerAccountSeedService:37-69、LegacyEmployeeSeedService ApplicationRunner:80-135 → auth_user、user_store_scope、brand/store_branch、profit_entry、employee 等。

- 不变量：正常生产 Web 服务绝不创建默认账号、共享密码、样例门店、样例利润、店长账号或遗留数据。
- 当前保护：开关默认 false；QA profile 有部分 false。
- 当前缺口：PRODUCTION 不拒绝这些开关；dev/demo profile 可以与 APP_ENV=PRODUCTION 并存；多数 seed 无整体事务。
- 最小复现：生产 APP_ENV 下显式设置任意 APP_BOOTSTRAP/APP_SEED 开关。
- 验收：最早 initializer fail closed；保留的一次性运维命令必须非 Web、受审计、显式启用、零默认秘密、单事务和退出码契约。

### A-05 P1：部署数据库契约冲突

AGENTS.md:97-103/109 和 docs/production-domain-waf-deployment.md:60-67 指向 store_profit_mysql8_final；DatabaseEnvironmentGuard:12、46-54 只接受 store_profit_mysql8。

验收：发布、SRE、数据库负责人书面冻结唯一数据库身份；运行守卫、CI、文档、脚本和测试共享该基线。

## 4. BR2-B：身份、授权与租户

BR2-B 在 A-01 通过前保持锁定；以下是完整整改链而非已执行 HTTP 攻击结论。

| 编号 | 入口→边界→持久化 | 主要缺口 | 验收 |
|---|---|---|---|
| B-01 P1 | GET /api/health → HealthController:45-94 → 每次 JDBC 查询 | 匿名回显环境、Flyway、DB version/port/database/current_user、检测 URL | 公共 liveness 仅 UP；诊断受网关/管理员保护 |
| B-02 P2 | POST login → AuthController:22-25 → AuthService:38-51,148-192 | 进程内无界 failedLogins；无 IP、TTL/容量、多实例可绕过 | 共享原子限流、账号+IP、TTL/容量、业务化 429/401 |
| B-03 P2 | AuthService:299-314 → AuthRepository:74-85 auth_token → Vue localStorage | 原始 bearer 存库且 12h 重放窗口 | token hash、轮换/短 TTL、登出失效、降低客户端暴露 |
| B-04 P2 | tenant 缺省→TenantDefaults=1；V2/V3/V37 FK | 单列 FK，DB 可产生跨租户错配 | 历史清洗后复合 (tenant_id,id) 约束，查询始终 tenant 前置 |
| B-05 P2 | storage upload/download → StorageService:281-401 | 通用 MIME 信任+inline；不是已证实 XSS 窃 token | 强制下载/隔离预览、MIME+魔数、审计一致 |
| B-06 P2 | CSV export → ExportController:160-254 | 未中和 = + - @ 公式 | 所有文本字段公式中和与回归 |
| B-07 P2 | assistant chat → data scope → provider | 经营上下文外发和本地限流多实例绕过 | 数据出境批准、最小化、共享预算/限流 |

正向控制必须保留：webhook HMAC/去重、AuthRepository 的 tenant/permissionVersion/expiry/enabled 校验、UserManagementService 同租户范围验证、Assistant 缓存的 tenant/role/modelContext hash。

## 5. BR2-C：库存、单据与多仓一致性

### C-01 P0：退货确认可并发重复入库

链路：

POST /api/warehouse/returns/{id}/receive → WarehouseService.receiveReturn:646-714 → WarehouseRepository:1365-1379、404-435 → warehouse_stock_batch、库存、门店库存、movement、operation_log。

- 当前保护：最终状态 update 带 APPROVED 谓词。
- 缺口：读取无 FOR UPDATE，先改库存再更新状态，且 update count 忽略。
- 最小复现：同一 APPROVED 退货 qty=1，门店存量至少两倍；两个请求均在读取 APPROVED 后并发。
- 验收：恰一份入库/扣店存/movement/待办动作，另一请求 409 或严格等幂等回放。

### C-02 P1：V43 调拨动作幂等键在并发下无效

链路：

WarehouseNetworkService:152-383 → WarehouseTopologyRepository.insertAction:350-378 → V43:512-529 unique(tenant, action_type, idempotency_key)。

当前 Service 先查 key、改业务状态/库存、最后 insertAction，且不处理 false。验收需先原子 claim key，claim 失败前不能有业务写；不同调拨单共享键并发后仅一张推进。

### C-03 P1：历史单据被列表上限截断

WarehouseRepository.requisition:810-865 只在 limit 80 列表中过滤；returnOrder:1236-1299 只在 limit 120 列表中过滤，审核/发货/收货/打印均依赖。

验收：按主键直查再做 tenant/store/角色范围授权，81/121 条后的旧合法单据仍可读、处理、打印。

### C-04 P2：库存与批次锁顺序相反

WarehouseService:1007-1081、1142-1147、1178-1183 的采购收货与预占/发货/调拨锁顺序相反。验收统一 inventory→batch 顺序，死锁可控重试并返回业务化结果。

## 6. BR2-D：财务、审批与异步

| 编号 | 写入链 | 缺口 | 验收不变量 |
|---|---|---|---|
| D-01 P1 | FinanceService:126-190 → FinanceRepository:77-128,186-206 | 无 expectedVersion，无条件 upsert/delete | 陈旧写 409，前后快照审计 |
| D-02 P1 | ExpenseService:95-223 → ExpenseRepository:105-245 | 审批状态无 expected status/version，普通保存可清审核字段 | 状态条件更新检查行数；终态不可被陈旧编辑 |
| D-03 P1 | SalaryWorkflowService:46-72 → SalaryRepository:630-752 | 普通保存绕过已有 version 方法 | 请求/SQL 同时限制 version+editable status |
| D-04 P1 | InspectionService:799-1014 → repository snapshots | 普通保存/bind 无锁，可覆盖已确认检测与快照 | 全部写共享 lock/version；终态保护 |
| D-05 P1 | Finance save → BusinessTodoService:195-295 → V18 unique | 先查后插，duplicate 可回滚利润 | 原子 upsert/锁/duplicate 重读；业务写与待办一致 |
| D-06 P2 | ProfitImportController:43-59 → ProfitImportService:169-260 | importId/rowId 未持久化；直接 commit 非幂等 | 批次/行键、操作者绑定、重试返回原结果 |
| D-07 P2 | AuditRepository:17-90 → response/controller | 可写 before/after 但查询丢弃，旧路径泛化 | 有权限的脱敏审计前后快照 |

## 7. 数据、迁移和角色的受控决策

以下不允许在没有书面决定时编码：

- D-BR2-03：V40 collation 兼容和历史升级策略。
- D-BR2-04：CI 最新版本唯一来源与 H2 缺版本差异清单。
- D-BR2-05：V3/V4/V7/V28 等 seed 逐类保留/清理规则；租户 1 不自动删除、合并或重新归属。
- D-BR2-06：ADMIN/OWNER 历史兼容、BOSS、tenantCode。
- D-BR2-07：库存/财务/审批最终状态机、409/回放语义。
- D-BR2-08：RTO/RPO、备份/敏感受控路径、密钥轮换。
- D-BR2-09：AI 数据出境、预算、共享限流。

## 8. 统一验收门禁

每个实现包都必须依序通过：

1. 远程 clean worktree 的单元/架构测试、后端 package、Vue build、git diff --check。
2. 真实隔离 MySQL 8 空库、目标升级库、失败/脏数据安全失败测试；禁止以 H2/Mock 代替。
3. 并发和幂等测试使用两个独立进程或连接，核对状态、库存、审计、Token/动作记录的精确计数。
4. 匿名、认证、越权、跨租户、跨店、附件、导出、AI 外发和操作日志的 HTTP/数据库证据。
5. 秘密扫描只报告路径/行号/计数，禁止写入密码、token、哈希、DB 凭据或密钥。
6. 只有上游包真实 PASS 才解锁依赖包；本蓝图不自动解锁 BR2-B/C/D。

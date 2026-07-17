# BR2-A-DF2：启动、迁移与发布恢复书面设计冻结

状态：待签署的设计冻结草案；仅用于决定 BR2-A 的后续实现边界，不授权编码、Flyway 修改、服务启动或生产发布。
日期：2026-07-16
关联执行计划：[backend-accident-remediation-execution-plan-v2.md](backend-accident-remediation-execution-plan-v2.md)
关联基线证据：output/release-evidence/BR2-00-R1-20260716/
关联签署与复核证据：output/release-evidence/BR2-A-DF2-20260716-140913/

## 1. 授权、正式基线与当前结论

张哲宇已书面指定下列对象为 BR2 正式“阻断基线”，并且仅授权本 DF2 书面设计冻结：

| 项目 | 已签署值 |
|---|---|
| 分支 | codex/deepseek-assistant |
| Commit | c83383ef2cbd8992ba8cbf6314ec5de758456530 |
| Tree | 4d14a9c8dc85bacec2f0e906417a84b9c0bd3673 |
| 阻断基线 JAR SHA-256 | 157B02F0AF53E9EAB6E999277AB35DB6C8DE56B5EED6CAB18F4B4BBF8F389074 |
| MySQL 事故事实 | MySQL 8.0.46 上 V1–V39 成功，V40 第 263 行以 HY000/1267 失败 |

2026-07-16T14:21:27+08:00 的实时 Git 复核再次确认远程分支为上述 Commit，且该 Commit 的 Tree 为上述 Tree。复核记录见 output/release-evidence/BR2-A-DF2-20260716-140913/remote-baseline-reverification.md。

本文件不表示下列任一结论：

- 不表示 JAR 可部署、可发布或可进入 QA。
- 不表示 BR2-A 已获编码授权。
- 不表示 V40 已修复，或任意 MySQL 系统验收已经通过。
- 不表示 BR2-B、BR2-C、BR2-D 获得解锁。

## 2. 设计范围与不可变边界

本 DF2 只覆盖 BR2-A 的五条事故链：

1. V40 在 MySQL 8 空库和升级链中的排序规则阻断。
2. 迁移前的真实数据库身份、授权、TLS 与目标库验证。
3. CI、脚本、候选 JAR 与发布链的 Flyway 版本契约。
4. 正常 Web 启动中 seed、bootstrap、legacy 数据写入的禁止与一次性运维命令边界。
5. 数据库命名、账号模型和发布恢复流程的单一正式契约。

以下边界在 DF2 中冻结，后续任何实现不得突破：

- V1–V57 为已发布迁移，不改写 V40，也不改写其他历史迁移的校验和。
- 新增 V58 或更高版本不能被表述为 V40 空库阻断的主修复：Flyway 到达 V58 前必先通过 V40。
- 不使用 baseline-on-migrate、out-of-order、替换 Flyway location、手工伪造 history、自动 clean 或自动删除失败记录。
- 不把 H2、Mock 或 692 个本地 H2 测试当作 MySQL 通过证据。
- 不连接、写入或停止生产、QA、3306、3307 或既有服务。
- 不覆盖、提交、推送、回滚或利用根工作区的 25 项用户 overlay；未来实施必须从签署 Commit 的独立干净 worktree 开始。
- BR2-A 不自动删除、合并或重归属租户 1、seed、员工、门店、财务或历史巡检数据；数据处置仍受单独决策约束。
- 本包不整改公开 health 信息、身份/租户、库存并发、财务/审批并发等 BR2-B/C/D 问题。

## 3. 事实校正：本 DF2 覆盖的旧基线表述

旧蓝图和旧执行计划中的 3d37、V54 或 V55 表述仅保留为当时的历史审计输入。针对 BR2-A，以下是 c833 正式阻断基线的可执行事实：

| 主题 | 正式事实 |
|---|---|
| MySQL 迁移链 | V1–V57，最新版本 V57 |
| H2 迁移链 | V0–V56；MySQL-only：22、23、24、25、26、27、28、32、49、51、57 |
| Bash 门禁 | verify-mysql-flyway.sh 与 verify-release-source.sh 均硬编码 V56 |
| PowerShell 候选路径 | 要求 MySQL/H2 的最新版本与文件名完全同步，和当前 H2 差异相冲突 |
| V40 根因 | V40 工作表未显式指定排序规则，继承数据库 utf8mb4_0900_ai_ci；目标表使用 utf8mb4_unicode_ci；跨排序规则比较在第 263 行失败 |
| 失败 V40 证据 | 完整残留 DDL/DML 快照未被取得；失败库必须作为独立恢复 cohort，不能假定 MySQL 自动完整回滚 |
| 发布源卫生 | c833 的 3d37..c833 范围有三处尾随空白；候选发布仍阻断 |
| 数据库身份 | DatabaseRuntimeIdentityGuard 当前为 no-op；DatabaseEnvironmentGuard 的库名与部署脚本的库名不一致 |

V40 的冲突不仅存在于第 291 行的 existing.code = template.code 比较；后续完整性校验还会比较同一模板与目标表字段。因此只为第 263 行加一个局部绕过不构成可接受设计。

原始证据文件中的日志文件名存在交叉引用差异：mysql-v40-reproduction.md 写入 133302，而实际存在且由 raw index 指定的产品失败日志为 133301。本 DF2 以 raw index 和磁盘清单为准，并将这一引用问题列为 A0 证据完整性校验项；它不改变 V40 事故结论。

## 4. 待签署的设计决策

下表的“推荐选择”是基于正式阻断基线的设计建议，不是已经批准的生产决定。所有 DF2-D01 至 DF2-D08 必须有唯一选择、签署人、日期和变更/工单编号，才能把本文件状态改为“设计冻结已签署”。

| 决策 | 推荐选择 | 明确拒绝 | 必需签署角色 |
|---|---|---|---|
| DF2-D01 基线 | 固定本文件第 1 节的 branch、commit、tree、JAR 和证据目录；远程再次不匹配即停止并做 delta 审计 | 用本地 tracking ref、脏目录或 output 快照代替远程基线 | 发布负责人、技术复核人 |
| DF2-D02 V40 兼容 | 采用“业务数据库默认 utf8mb4 / utf8mb4_unicode_ci 预规范化 + 分类恢复” | 改写 V40、仅新增 V58、跳过 V40、JDBC SET NAMES 伪修复 | DBA、发布负责人、技术复核人 |
| DF2-D03 失败 V40 | 先只读取证和分型；只对精确 C2 cohort 在备份和逐项批准后执行一次 repair + rerun | 自动 repair、clean、删 history、猜测性清理 | DBA、数据所有者、发布负责人 |
| DF2-D04 迁移契约 | MySQL 目录为发布权威链；单一机器可读迁移契约向 Bash、PowerShell、CI、JAR 清单供给版本和 H2 allow-list | 硬编码最新版本、要求 H2 与 MySQL 文件完全同步、以 H2 代替 MySQL | CI 所有者、DBA、技术复核人 |
| DF2-D05 身份守卫 | 迁移与 Web 分阶段真实 JDBC 验证；错误版本/库/端口/账号/TLS/授权均在 Flyway 前失败关闭 | 仅检查 URL 或用户名文本、Web 启动后再检查、root 或全局授权例外 | 安全负责人、DBA、SRE |
| DF2-D06 数据库契约 | 由 SRE/DBA 选择唯一正式数据库名、端口、TLS、迁移账号和运行账号；默认分离账号 | 同时接受 store_profit_mysql8 与 store_profit_mysql8_final、生产共享 root | SRE、DBA、发布负责人 |
| DF2-D07 seed/bootstrap | 正常 Web 永久拒绝所有 seed/bootstrap/legacy/migration-auto 开关；若保留 CLI，必须非 Web、显式、无默认秘密、审计、事务和退出码受控 | HTTP seed、生产默认账号/样例数据、租户 1 自动处置 | 业务数据负责人、安全负责人、发布负责人 |
| DF2-D08 发布与恢复 | 先处置发布源卫生和受控路径；确定备份、RTO/RPO、密钥轮换、恢复演练和候选 JAR 供应链 | 未审批删除/历史重写、任意 JAR 直接部署、在证据中记录秘密 | 发布负责人、安全负责人、DBA、SRE |

## 5. 冻结的技术设计

### 5.1 数据库排序规则契约与 V40 前置条件

推荐的业务数据库级契约为：

    character set: utf8mb4
    database default collation: utf8mb4_unicode_ci

MySQL 服务器默认排序规则可以是 utf8mb4_0900_ai_ci；DF2 限定的是业务数据库自身的默认值。这与 V1、V17 和 V40 已显式声明的 utf8mb4_unicode_ci 表/列语义一致。

在 V40 前必须存在一个受控的、一次性、非 Web 的迁移预检/恢复作业。该作业先只读完成以下检查：

1. 签署 commit/tree、候选 JAR、迁移契约和干净 worktree 是否一致。
2. MySQL 版本、端口、数据库、DATABASE()、CURRENT_USER()、TLS 状态和 SHOW GRANTS 是否符合 DF2-D05/D06。
3. information_schema 中数据库默认字符集/排序规则，及关键 V17/V40 表、列的排序规则。
4. Flyway history 的连续性、成功/失败记录、V40 描述与校验和。
5. V40 相关对象、例程、模板表、inspection_result_repair_audit、修正版标准、历史巡检记录的存在性、定义和安全摘要。
6. 变更窗口、备份标识、恢复负责人、审批编号、数据库 advisory lock 是否齐备。

只有预检分类明确且相关签署有效时，作业才可在数据库级执行默认排序规则规范化。正常 Web 服务绝不能执行 ALTER DATABASE、Flyway repair 或 V40 恢复。

### 5.2 V40 恢复 cohort 与允许动作

| cohort | 识别条件 | 唯一允许的后续动作 |
|---|---|---|
| C0 新空库 | 无业务表或 Flyway history | 创建/规范化数据库默认值后，完整迁移 |
| C1 V39 升级库 | V1–V39 连续成功、无 V40 历史 | 备份和审批后规范化默认值，再迁移 |
| C2 已知失败 V40 | V1–V39 连续成功；仅一条 V40 success=0；无 V41+；对象与数据符合预定义允许画像 | 完整快照 → 规范化 → 仅一次获批 repair → 完整 migrate |
| C3 已成功 V40+ | V40 和后续版本连续成功 | 恢复作业拒绝或 no-op；不得重跑、repair 或重建历史 |
| C4 未知/污染 | 多条失败、残留不符、V40 已激活但数据不完整、存在未解释后续版本 | 硬停止；只允许克隆取证和人工恢复方案，禁止 repair |

C2 的允许画像至少要求：

- V1–V39 没有缺号、重复或失败；V40 文件版本、描述和校验和与候选一致。
- 不存在 V41+ history；原 ACTIVE 标准未被提前归档。
- 每个租户中 2025.11.06-R1 的状态、分数、日期和条目数符合第 263 行失败前的预期画像。
- inspection_result_repair_audit 与 v40_quality_item_template 的定义、行数和是否存在都被捕获。
- 不符合任一条件直接转入 C4，不允许“尝试修复看看”。

ALTER DATABASE 与 MySQL DDL 不被视为事务性可回滚操作；Flyway repair 也不是业务回退。对 C2，默认生产恢复路径是从经验证的备份克隆进行诊断和前向恢复。若 repair 后或 migrate 中再次失败，立即保存第一次失败证据、隔离库，禁止第二次 repair、手改 history 或 clean。

### 5.3 启动、迁移与数据库身份模型

推荐的目标模型如下：

    受批准的迁移/恢复作业
      → 真实 JDBC 身份与目标验证
      → cohort 分类、备份与锁
      → 受控 Flyway migrate
      → SQL 验收与证据
      → 退出

    正常 Web 进程
      → 真实 JDBC 身份与目标验证
      → 断言目标 Flyway 版本、连续性和 failed=0
      → 拒绝 seed/bootstrap/legacy/migration-auto
      → 才绑定 HTTP 端口

DF2-D05 未签署前不得实现。签署后的守卫必须在任何 Flyway history 写入和 HTTP 监听前验证：

- 受支持的 MySQL 版本；实际 @@port、DATABASE()、CURRENT_USER() 和数据库名称。
- 迁移账号、运行账号与 DF2-D06 的明确角色模型；账号不是 root。
- 不存在 *.*、GRANT OPTION 或未批准的全局权限；只允许批准 schema 的最小权限。
- 正式环境 TLS 为 VERIFY_IDENTITY，且错误 TLS、public-key retrieval 风险或账号不符均失败关闭。
- 错库、错端口、错误账号、过大授权和不符合 TLS 时，必须证明零 Flyway 写入、零 Web 监听。

现有公开 health 内容不应用作身份证据。它在本包中最多作为 liveness；其公开信息泄露整改仍在 BR2-B 范围。

### 5.4 正常 Web 的 seed/bootstrap 边界

实施后，APP_ENV=PRODUCTION 的正常 Web 服务必须在最早初始化阶段拒绝所有会引发运行时写入的 seed/bootstrap/legacy/migration-auto 开关及 --admin-bootstrap 组合。拒绝必须发生在数据库写入和 HTTP 监听前。

如保留一次性管理 CLI，它必须满足：

- 非 Web、显式命令、显式启用，且不能由应用正常启动隐式触发。
- 无默认账号、密码、token 或样例业务数据。
- 使用批准的非 root 迁移/管理身份，写入操作日志并返回固定退出码。
- 一次命令只处理批准范围，并在一个可验证事务边界内失败关闭。
- 不承担 seed 清理、租户 1 处置或 BR2-B/C/D 的业务修复。

### 5.5 迁移契约、候选 JAR 与发布门禁

未来实施必须生成一个单一、机器可读的迁移契约，而不是维护散落的最新版本常量。契约至少包含：

- MySQL 版本清单、最新版本、连续性、每版本唯一性、文件名和内容摘要。
- H2 版本清单，以及每个 MySQL-only 版本的逐项 allow-list：不适用、语义折叠或替代测试、理由和负责人。
- 当前 MySQL V57 与 H2 V56 的差异处理；H2 不可作为 MySQL 发布验收。
- 候选 commit/tree、git archive 摘要、JAR SHA-256、前端构建摘要和工具版本。

Bash、PowerShell、GitHub Actions、候选清单、QA 启动器和部署器必须读取该契约。实现时必须删除 V56 硬编码和“两个目录最新文件必须完全相同”的隐式假设。

候选构建顺序冻结为：

1. 从获批 BR2-A 实施 commit 创建 detached、干净 worktree。
2. 校验工作区无 overlay、remote commit/tree 一致、release-source hygiene 和秘密扫描通过。
3. 从 git archive 构建后端与前端产物；候选目录位于源码树外。
4. 生成并签名候选清单，绑定源码、迁移契约、JAR、前端产物和工具版本。
5. MySQL 8 隔离矩阵只接受候选目录和清单，不接受任意 JarPath 或 output 历史快照。
6. 只有所有门禁通过后，部署器才可使用批准的非 root 发布账号原子切换应用文件。

## 6. 分阶段实施设计与停止规则

| 阶段 | 未来实施范围 | 进入下一阶段的硬条件 |
|---|---|---|
| A0 | 建立干净 worktree、候选契约、证据引用校正与签署确认 | remote/tree/JAR 一致；DF2-D01 至 D08 已签署；overlay 已排除 |
| A1 | V40 预检、排序规则兼容与 cohort 恢复作业 | C0、C1、C2 的真实隔离 MySQL 8 验收均通过，C3/C4 拒绝语义通过 |
| A2 | 真实 migration/runtime identity guard 与数据库契约 | 正向账号通过；全部负向身份在 Flyway 前拒绝且不监听 |
| A3 | Web seed/bootstrap fail-closed 与受控 CLI 边界 | 生产组合全部拒绝；正常 Web 的账号、组织、门店、利润、员工计数为零变化 |
| A4 | CI/脚本/候选 JAR 单一迁移契约与发布源门禁 | MySQL/H2 差异明确受控；源码、JAR、CI 与脚本一致 |
| A5 | 完整隔离 MySQL 集成验收、清理和签署 | 全链证据完整、失败记录为零、临时资源清理、BR2-A 集成验收签署 |

每阶段严格执行：待实施 → 本地回归 → 隔离 MySQL 8 验证 → 证据复核与签署 → 下一阶段。任一失败立即停止，保存首个失败证据，不以不同参数重跑覆盖原结果。

立即停止条件包括但不限于：

- remote commit/tree、候选 JAR、迁移契约或候选清单不匹配。
- 工作区不干净、源码卫生/秘密扫描失败，或候选来自根目录 overlay/output 快照。
- V40 或任一后续迁移失败、出现 success=0、checksum 不一致或非预期残留。
- 未完成备份和 C2 取证就尝试 repair；任意 C4 被自动处理。
- 身份守卫放过错误库/端口、root、全局授权、GRANT OPTION 或不合格 TLS。
- 正常 Web 服务产生 seed/bootstrap/样例业务写入。
- 隔离作业触达 3306、3307、QA 或生产。
- 试图因 BR2-A 设计、单元测试或局部通过而解锁 BR2-B/C/D。

## 7. 隔离 MySQL 8 验收矩阵

| 场景 | 预期结论 | 必留证据 |
|---|---|---|
| 基线复现 | 服务器/数据库 0900 下，V1–V39 成功，V40 第 263 行 HY000/1267 | 原始日志、history、MySQL 版本与排序规则摘要 |
| C0 正向 | 服务器 0900、业务数据库 unicode；V1–最新全部成功 | 默认值前后、连续 history、failed=0、JAR/契约摘要 |
| C0 负向 | 数据库默认值不符合时预检失败关闭，不进入 Flyway | 零 history 写入、无监听、退出码 |
| C1 V39 升级 | 保留历史巡检数据，规范化后完整升级 | 前后行计数/摘要、V40 不变量、failed=0 |
| C2 失败 V40 | 先完整取证，再仅一次获批 repair + rerun | 备份标识、对象清单、审批、repair 前后 history |
| C3 V40+ | 恢复作业拒绝或 no-op，业务和 history 不变 | 前后摘要相同、退出码 |
| C4 污染 | 恢复作业硬拒绝且零写入 | 拒绝原因、history/对象摘要不变 |
| 身份负向 | 错库/端口、root、全局授权、GRANT OPTION、TLS 不符均在 Flyway 前失败 | 无监听、history/关键表前后不变 |
| Web/seed | 生产 seed/bootstrap/legacy/migration-auto 组合逐项拒绝；正常启动零 seed 写入 | auth_user、组织、门店、利润、员工前后计数 |
| CI/供应链 | 版本契约、JAR 条目、release-source、秘密扫描、MySQL 矩阵全部通过 | 命令退出码、候选清单、摘要和清理记录 |

V40 业务不变量至少包括：每租户修正版标准、105 个条目、40/47/18 分类数、37/63/100 分、红黄线统计、唯一性和历史标准/巡检快照的保留。后续 V41/V42 可能合法写入 repair audit，因此最终验收不能错误要求 inspection_result_repair_audit 恒为零。

## 8. 证据、签署与后续解锁

每个未来 BR2-A 实施批次必须保留脱敏证据：

- 签署的 commit/tree/JAR/迁移契约身份和远程复核结果。
- 命令、退出码、工具版本、MySQL 版本、排序规则矩阵、Flyway 成功/失败计数和目标版本。
- C2 的备份标识、残留对象/行数摘要、审批和恢复收据。
- 守卫负向测试的 history 与关键表计数前后对比；seed 写入前后对比。
- 候选 JAR 内迁移清单、候选清单、秘密扫描和 release-source 结果。
- Java/端口/临时目录/隔离 MySQL 清理结果。

证据只能记录安全摘要、路径、行号、计数和获批产物哈希；不得记录密码、token、数据库密码、完整连接串、私钥、密码哈希或 provider key。

签署模板：

| 项目 | 签署人/角色 | 日期 | 工单/变更号 | 结论 |
|---|---|---|---|---|
| DF2-D01 基线 |  |  |  | [ ] 同意 [ ] 拒绝 |
| DF2-D02 V40 兼容 |  |  |  | [ ] 同意 [ ] 拒绝 |
| DF2-D03 失败 V40 |  |  |  | [ ] 同意 [ ] 拒绝 |
| DF2-D04 迁移契约 |  |  |  | [ ] 同意 [ ] 拒绝 |
| DF2-D05 身份守卫 |  |  |  | [ ] 同意 [ ] 拒绝 |
| DF2-D06 数据库契约 |  |  |  | [ ] 同意 [ ] 拒绝 |
| DF2-D07 seed/bootstrap |  |  |  | [ ] 同意 [ ] 拒绝 |
| DF2-D08 发布/恢复 |  |  |  | [ ] 同意 [ ] 拒绝 |
| DF2 设计冻结 | 发布、DBA、安全、SRE、业务负责人 |  |  | [ ] 仅允许申请编码授权 |

DF2 全部签署后，下一步仍不是自动编码：应由授权人单独下达“BR2-A A0/A1 编码与隔离验收”授权。只有 A1–A5 均在真实隔离 MySQL 8 通过、BR2-A 集成验收书面签署，并满足各自额外决策后，BR2-B、BR2-C、BR2-D 才可分别进入设计阶段。

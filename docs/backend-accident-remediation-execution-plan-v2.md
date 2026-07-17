# AI Profit OS 后端事故整改执行计划 v2

状态：BR2-00-R1 的正式阻断基线已签署并实时复核；BR2-A-DF2 设计冻结草案待逐项签署，当前仍不具备发布/系统验收通过状态。
正式阻断基线：codex/deepseek-assistant @ c83383ef2cbd8992ba8cbf6314ec5de758456530 / tree 4d14a9c8dc85bacec2f0e906417a84b9c0bd3673。
历史说明：原 3d37 基线与 V54/V55 表述仅保留为历史审计输入；BR2-A 当前事实以 BR2-00-R1 和 BR2-A-DF2 为准。
本计划不替代生产变更审批，也不授权连接生产、3306 或 3307。

## 0. BR2-00 完成记录

| 项目 | 状态 |
|---|---|
| 远程 commit/tree 冻结 | 已完成：3d37ef6 / 2b872e9 |
| 本地覆盖层隔离 | 已完成：修改前快照 79 行，未重置/回滚/提交 |
| 四域全链静态审计 | 已完成：见 v2 蓝图与证据矩阵 |
| 每域真实复现或审计阻断 | 已完成：BR2-A 有真实 MySQL 8 复现；BR2-B/C/D 被同一 V40 启动失败阻断并明确记录 |
| 后端测试 | FAIL，首次失败保留：QualityInspectionStandardRepairMigrationTest |
| 后端 package | PASS |
| Vue build | PASS |
| GitHub 发布源门禁 | FAIL：V54/V55 脱节及受控路径/敏感配置候选 |
| 隔离 MySQL 空库 | FAIL：V40 collation 1267；临时资源已清理 |
| 产品代码/Flyway/配置/部署改动 | 无 |
| 现有服务/3306/3307 | 未连接、未停止、未写入 |
| BR2-00 任务状态 | 已完成（事故基线和失败证据已冻结），不等于发布通过 |
| 当前服务地址 | 未启动 |

证据目录：output/release-evidence/BR2-00-20260716-105934/。
旧方案处置：保留为历史输入，当前执行以 backend-accident-remediation-blueprint-v2.md 为准。

## 0.1 BR2-00-R1 基线替换与 DF2 状态

| 项目 | 状态 |
|---|---|
| 正式阻断基线 | 已由张哲宇签署：c83383e / 4d14a9c / JAR 157B02F0…9074；不是发布通过 |
| 远程复核 | 2026-07-16 已实时 PASS；记录见 output/release-evidence/BR2-A-DF2-20260716-140913/remote-baseline-reverification.md |
| MySQL 当前事故 | V1–V39 成功，V40 第 263 行 HY000/1267 失败；V1–V57 是当前 MySQL 源链 |
| BR2-A-DF2 | 已创建设计冻结草案 docs/backend-accident-remediation-br2-a-df2.md，待 DF2-D01 至 DF2-D08 签署 |
| DF2 签署包 | 已生成 output/release-evidence/BR2-A-DF2-20260716-140913/df2-decision-signoff-pack.md；D06/D07/D08 含必须由负责人填写的生产决策 |
| DF2 签署完整性 | 未通过：收到“DF2 已签署”声明，但现有签署包仍为空模板；详见 output/release-evidence/BR2-A-DF2-20260716-140913/df2-signoff-completeness-audit.md |
| DF2 文档门禁 | 已通过：范围内 diff --check、尾随空白、决策/章节完整性和秘密字面量扫描；未运行代码构建或 MySQL，因为本次只有文档变更 |
| BR2-A 编码/Flyway | 继续锁定；本次只修改文档和证据说明 |
| BR2-B/C/D | 继续锁定 |

## 1. 包状态

| 包 | 状态 | 解锁条件 |
|---|---|---|
| BR2-00 | 已完成（基线证据冻结） | 不适用 |
| BR2-A 启动/迁移/发布 | 设计冻结草案待签署，不开始编码 | DF2-D01 至 DF2-D08 和单独的 BR2-A 实施授权 |
| BR2-B 身份/授权/租户 | 锁定 | BR2-A 真实 MySQL 通过，且 D-BR2-06、09 已决 |
| BR2-C 库存/单据一致性 | 锁定 | BR2-A 真实 MySQL 通过，且 D-BR2-05、07 已决 |
| BR2-D 财务/审批/异步 | 锁定 | BR2-A 真实 MySQL 通过，且 D-BR2-07、08 已决 |

本次没有开始 BR2-A/B/C/D 代码；仅完成 BR2-A-DF2 书面设计草案和远程基线复核。

## 2. BR2-A 设计冻结清单

正式草案见 docs/backend-accident-remediation-br2-a-df2.md。该文档以 c833/V57 事实替代本节中旧的 V54/V55 历史描述；未签署前不授权编码。

在任何 Java、Vue、配置、脚本或 Flyway 改动前，发布、数据库、安全和业务负责人必须签署：

- V40 兼容策略：空库与 V39 升级库的排序规则/字符集支持矩阵、失败恢复方式、append-only 迁移版本、回退不可逆说明。
- CI 版本来源：如何从源码计算/验证最新版本；MySQL/H2 十个差异版本是否有逐项批准的语义折叠；不可只更新 V54 常量。
- 运行/迁移账号：是否共用、最小授权、TLS、SHOW GRANTS、root/global privilege 失败语义。
- 启动 seed：正常 Web 服务必须永久禁止的开关，保留的一次性运维命令如何受控；不得把它改成 HTTP 接口。
- 租户 1 与 V3/V4/V7/V28：数据所有者给出保存、不可用化、清理候选、审计/备份/恢复 SLA；无法证明安全的一律保留。
- 发布源门禁阻断路径：备份文件分类/外部保留、疑似敏感配置轮换，禁止在未获批准时删除或历史重写。
- 运行数据库命名：文档、守卫、CI、QA 和部署脚本的唯一正式标识。

## 3. BR2-A 实施和验收门禁（未来）

1. 记录 git status before，创建远程 HEAD clean worktree；不覆盖任何本地 overlay。
2. 历史 V1–V57 不改写；未来新增迁移只能高于 V57，且不能替代 V40 前置兼容/恢复方案。
3. 为 V40 增加真实 MySQL 8 空库、V39 升级、受影响历史数据、不同批准 collation 的测试。
4. 实现并测试真实 runtime identity guard；按真实连接校验 version、port、database、current_user、SHOW GRANTS。
5. 生产启动 fail closed：所有 seed/bootstrap/legacy/migration-auto 开关拒绝，正常 Web 不能建账号/样例数据。
6. 修复并实际运行 CI 门禁；检查迁移连续性、MySQL/H2 差异 allow-list、JAR 条目、秘密扫描。
7. 在随机隔离 MySQL 8 运行候选 JAR，验证 health、Flyway latest/failed、无种子写入、临时进程/端口/目录清理。
8. 任何一步失败即停止，保存首个失败证据；不在同一包启动 BR2-B/C/D。

## 4. 后续包的具体解锁验证

### BR2-B

- 空库成功启动后，验证匿名 health、错误/正确登录、disabled 用户、logout/token 失效、权限版本失效、跨 tenant/store scope、附件和 CSV。
- 真实并发/容量测试要跨进程/实例，不以单 JVM ConcurrentHashMap 代替。
- AI 外发前必须有批准的测试 provider/数据最小化证据。

### BR2-C

- 两独立连接并发退货 receive、不同调拨单同 action key、采购/发货锁顺序、81/121 历史单据。
- 精确核对批次、库存、movement、状态、operation_log、todo/action records；冲突必须业务化而非 500。

### BR2-D

- 两独立连接并发利润、报销、工资、巡检、待办生成和重复导入。
- 精确核对 version、状态、财务行、审计 before/after、import/action keys；陈旧写必须 409 或严格回放。

## 5. 证据和秘密规则

- 每包要有提交/tree/JAR hash、命令退出码、MySQL 版本/端口仅以安全摘要记录、Flyway 版本/失败数、HTTP 状态、表计数和清理结果。
- 不得在日志、异常、证据、文档或提交中记录测试密码、数据库密码、token、hash 或 provider key。
- 现有发布源脚本发现的受控路径由所有者处置；本计划不授权删除、git clean、git reset、git checkout、提交、推送或历史重写。

## 6. 当前唯一建议

当前仅推进 BR2-A-DF2 的逐项签署和后续实施授权申请；签署前不得启动最小启动/迁移恢复包编码。
BR2-B、BR2-C、BR2-D 继续锁定；不得因 BR2-00 完成而自动开始编码。

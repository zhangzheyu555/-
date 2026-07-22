# G0 环境与测试基线台账

执行批次：`E2E-20260720`
执行时间：2026-07-20（本机隔离 QA）
范围：桌面 Web；不包含移动端。

## 当前结论

`G0 PASS`。六项前置门禁均已完成；下一步可按顺序进入 G1。

## 已验证证据

| 编号 | 结果 | 证据 / 结论 |
| --- | --- | --- |
| G0-01 版本冻结 | PASS | Git 基线 `398cda73f53a4586a99e017e8bdb0d99ca51c761`；当前受测工作区候选以后端 JAR SHA-256 `b00dc7b93bbe833635a3596c25106c603d1bf36d2af70b2730ad859ce63c3c7d`、前端入口 SHA-256 `c3d86e9cb969e60a34d0fc1b6a8a4197d02f8bc812b2cf1adebe8bd3935d3318` 和 Flyway `V74` 唯一标识。 |
| G0-02 隔离 QA 环境 | PASS | 本机 Docker Compose 服务均健康；网关、前端、后端、巡检服务健康端点均为 HTTP 200；数据库 `ai_profit_dev_qa`。 |
| G0-03 可恢复基线 | PASS | 基线备份 SHA-256 `4683d53cff4134edf73507ca83faaba42a1bb38256365aa8f68e7ec3e98c9125` 已恢复至独立库 `ai_profit_dev_qa_restore_20260720`；恢复库 Flyway V72、102 张表、1 租户、0 账号、0 门店。当前测试库未被覆盖。 |
| G0-04 受控账号 | PASS | BOSS、FINANCE、WAREHOUSE、两名 STORE_MANAGER、SUPERVISOR、两名 EMPLOYEE、DENY 均已创建并登录验证。店长 A/B 仅可见各自门店；DENY 现为 58 项显式 DENY、0 有效权限、默认 `NO_WORKSPACE` 且无可见门店；新建 `OPERATIONS` 被 `ROLE_LEGACY_REJECTED` 明确拒绝。 |
| G0-05 基础数据 | PASS | 两个门店、总仓/区域仓、活跃/停用物料、库存批次、供应商、两名已关联账号的员工、培训资料、课程、无敏感 PNG 附件样本及本地 QMAI Mock 均已验证。附件由老板下载为 200，门店 B 店长跨店读取为 403。 |
| G0-06 证据目录 | PASS | 当前目录及本台账已建立。 |

## 受控数据摘要

- 门店：`e2e-20260720-store-a`、`e2e-20260720-store-b`。
- 物料：`E2E-20260720-MAT-ACTIVE`（启用）、`E2E-20260720-MAT-INACTIVE`（停用）。
- 员工：`E2E Employee A`、`E2E Employee B`；当前尚未关联员工登录账号。
- 课程：`E2E-20260720-COURSE-01`。
- QMAI：`qmai-mock` 仅在 Docker 内部暴露；`ruguo` 配置指向 `http://qmai-mock:8080`，成功拉取、受控 404 失败和财务配置写入 403 均已验证。
- 网关：已改为通过 Docker DNS 动态解析后端/前端，解决容器重建后可能保留旧 IP 的开发部署问题。
- 密码、Token、数据库密码和任何密钥均未写入本文件。

## G0 放行所需下一步

V74 候选已按测试计划重新执行 G1；任一后续源码或迁移变更仍须从受影响关卡首例重测。

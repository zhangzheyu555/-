# 内部试用逐关测试台账

执行批次：`E2E-20260720`
范围：桌面 Web；不包含移动端。

| 关卡 | 状态 | 证据 |
| --- | --- | --- |
| G0 环境与测试基线 | PASS | [G0 台账](00-environment-and-baseline/g0-gate-ledger.md) |
| G1 构建、迁移与基础安全 | PASS | [G1 台账](01-build-and-migration/g1-gate-ledger.md) |
| G2 六角色访问与范围 | PASS | [G2 台账](02-role-access/g2-gate-ledger.md)；两项 P1 已修复并回归。 |
| G3 45 项模块功能 | WAIVED FOR G4 | 用户已于 2026-07-21 明确授权 QMAI-04 后续补齐并允许进入 G4；44 项 PASS、QMAI-04 仍为 DEFERRED，不能标记为 45/45 PASS 或作为最终签署依据。 |
| G4 跨角色闭环 | BLOCKED | [FLOW-01](04-cross-role-flows/flow-01-qa-acceptance.md)～[FLOW-06](04-cross-role-flows/flow-06-qa-acceptance.md) 已有隔离 QA 通过证据；[FLOW-07](04-cross-role-flows/g4-flow-07-local-mock-audit.md) 按“内部受控 YOLO、不建设图片内容审核”的产品决定完成内部试用最小门禁并标记 **PASS（MINIMUM INTERNAL PILOT ONLY）**。真实无结果/非法响应/重定向、真实持久化确认撤销、经营与员工助手本轮重验等仍延期，因此不能写为完整 FLOW-07/G4 PASS，且不得进入 G5。 |
| G5 外部能力、文件与故障 | PENDING | G4 为 BLOCKED；不得进入。 |
| G6 安全、并发、恢复与性能 | PENDING | 不得在 G5 前执行。 |
| G7 复核与签署 | PENDING | 全部前置门禁通过后执行。 |

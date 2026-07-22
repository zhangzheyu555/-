# QMAI-01～QMAI-04 修复后的定向预验收

- 检查时间：2026-07-21 CST
- 范围：仅本地代码和进程内 H2；未连接 QA MySQL、Docker、局域网或生产；未提供、读取或输出真实凭证/token；未启动浏览器或外网服务。
- 结论：**BLOCKED（尚未完成完整桌面隔离验收）**。

## 已落实的安全与服务端边界

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| QMAI-01 凭证安全 | PASS（定向） | 新保存的 openId、grantCode、openKey、后台账号/密码/token 采用部署变量 `QMAI_CREDENTIAL_ENCRYPTION_KEY` 指定的 AES-GCM 密钥加密；缺失或无效密钥拒绝保存。旧明文行不会被静默重用，需管理员重新保存。读取仍只返回掩码/是否配置。 |
| QMAI 出站保护 | PASS（定向） | 默认 `QMAI_OUTBOUND_MODE=DISABLED`；`MOCK` 仅允许 loopback 地址；真实外网必须显式 `LIVE`。开放平台和后台令牌通道均在实际请求前执行该守卫。 |
| 配置审计 | PASS（定向） | 成功保存配置写 `operation_log`，审计原因不包含凭证明文；现有统一权限拒绝审计保持生效。 |
| QMAI-02 / QMAI-03 本地快照 | PASS（定向） | 新增受租户、品牌、门店范围约束的营业额/商品销量查询与 CSV 下载端点；查询和导出均写审计，CSV 对公式前缀转义。导出读取已有 `qmai_daily_sales` / `qmai_product_sales` 本地快照，不发起外网请求。 |
| QMAI-04 服务端计算 | PASS（定向） | 新增 BigDecimal 配方用量计算端点，返回不可变计算快照并记录审计；非法配方/负值/零出肉率拒绝。前端现有完整配方目录尚未迁入服务端。 |

## 定向验证

| 命令/场景 | 结果 |
| --- | --- |
| `mvn -q -Dtest=QmaiSecurityAndRecipeTest test` | PASS（4/4）：密文不含合成秘密、旧明文不复用、外网默认拒绝/loopback Mock 放行、BigDecimal 毛重快照、H2 租户与门店范围、H2 Flyway 至 V76。 |
| H2 Flyway `db/migration-h2` 至 V76 | PASS：企迈配置表已与当前后端加密配置列对齐。 |

## 尚未完成的门禁与最小解锁动作

1. **完整接口授权验收未执行**：需补充 Controller/H2 用例，实际验证 BOSS/SUPERVISOR/受授权 FINANCE 成功，STORE_MANAGER/WAREHOUSE/EMPLOYEE 403、匿名 401、跨品牌/跨门店 403，以及查询/导出/拒绝审计。
2. **QMAI-04 不是完整业务闭环**：现有完整配方目录仍位于前端静态数据。必须将受版本控制的配方目录迁入服务端受管数据源，并让“销量填充、计算、导出”统一调用服务端快照/导出，才能声称 QMAI-04 PASS。
3. **桌面 Chromium 未执行**：待上述 H2/Mock 接口测试完善后，以 1280px 浏览器验证空态、范围筛选、后端下载审计及零控制台错误。
4. **全量回归未执行**：按并发协调约束，本次未运行后端全量、打包或前端类型/生产构建；由主线程串行执行。

本报告没有更新 G3 台账；QMAI-01～QMAI-04 继续保持 BLOCKED，未进入 AI/GOV/FLOW。

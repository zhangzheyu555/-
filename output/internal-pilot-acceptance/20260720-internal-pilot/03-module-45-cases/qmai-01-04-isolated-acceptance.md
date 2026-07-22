# QMAI-01～QMAI-04 隔离验收记录

- 检查时间：2026-07-21 16:00 CST
- 范围：仅桌面 Web；QMAI-01（凭证与品牌配置）、QMAI-02（营业额）、QMAI-03（商品销量）、QMAI-04（配方销量/用量测算）。
- 结论：**BLOCKED**。
- 隔离承诺：本次未写入或读取真实凭证、真实 token、生产数据；未调用企迈服务或任何外网业务地址；未启动浏览器验收或本地服务；未连接 QA MySQL/生产数据库。

## 门禁结果

| 用例 | 状态 | 阻断证据 |
| --- | --- | --- |
| QMAI-01 | BLOCKED | 配置数据表直接保存 `open_key`、`console_password`、`console_token`；未发现加密/密钥引用机制，也未发现 QMAI 配置、拒绝或导出审计写入。 |
| QMAI-02 | BLOCKED | 仅存在 `/console-income` 与 `/summary` 查询；未提供营业额导出端点，且没有可注入的本地 Mock 网关或 QMAI H2 验收测试。 |
| QMAI-03 | BLOCKED | 未发现商品销量专用查询/导出端点或 QMAI 测试类，无法在合成 H2 数据中证明品牌/门店/租户范围、403/401 与导出审计。 |
| QMAI-04 | BLOCKED | 配方用量与 CSV 下载位于浏览器页面；没有服务端 H2 持久化/权限范围/审计路径，不能证明 BigDecimal 快照、跨范围 403 与导出审计。 |

## 静态预检证据

- [QmaiController.java](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/backend/src/main/java/com/storeprofit/system/qmai/QmaiController.java:50) 的路由仅包括状态、配置、探测、`console-income` 和 `summary`，不存在营业额或商品销量导出路由。
- [QmaiConfigRepository.java](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/backend/src/main/java/com/storeprofit/system/qmai/QmaiConfigRepository.java:18) 读取并上写敏感配置；[V63](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/backend/src/main/resources/db/migration/V63__qmai_platform_config.sql:10)、[V64](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/backend/src/main/resources/db/migration/V64__qmai_config_brand.sql:12)、[V65](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/backend/src/main/resources/db/migration/V65__qmai_console_token.sql:5) 为明文字段定义。
- [QmaiConfigService.java](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/backend/src/main/java/com/storeprofit/system/qmai/QmaiConfigService.java:104) 允许保存任意 `baseUrl`；[QmaiOrderService.java](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/backend/src/main/java/com/storeprofit/system/qmai/QmaiOrderService.java:402) 直接按该地址发起 HTTP 请求。当前没有可控的测试 Mock 适配器或外网拒绝守卫。
- QMAI 包内未检索到 `operation log`/`audit` 写入，测试目录中也未检索到 QMAI 专用测试类。
- [PlatformLoginPage.vue](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/frontend-vue/src/pages/PlatformLoginPage.vue:438) 在前端执行配方杯数填充、用量计算和 CSV 下载；这不能替代 H2 数据范围、权限及审计验收。

## 已执行的非外网构建证据

| 命令 | 结果 |
| --- | --- |
| `cd backend && source /Users/a1/.zprofile && mvn -q test` | PASS：172 个套件、833 项测试，0 failure、0 error、0 skipped。该全量结果不包含 QMAI 专用 H2/Mock 场景。 |
| `cd backend && mvn -q -DskipTests package` | PASS：生成 `target/store-profit-backend-0.1.0-SNAPSHOT.jar`。 |
| `cd frontend-vue && PATH=/Users/a1/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin:$PATH ./node_modules/.bin/vue-tsc -b` | PASS。 |
| `cd frontend-vue && PATH=/Users/a1/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin:$PATH ./node_modules/.bin/vite build` | PASS。 |

## 未执行项与清理

- 定向 QMAI H2/Mock 测试：NOT RUN。项目没有可安全执行的 QMAI Mock/H2 验收路径；不以真实或未知地址代替 Mock。
- 1280px Chromium：NOT RUN。没有隔离 H2 + Mock 的运行实例，不能以现有本地 MySQL/服务冒充本用例证据。
- 本次没有创建测试账号、会话、H2 文件、数据库记录、Mock 进程、导出文件或审计记录，因此无需额外清理；基线未改写。

## 最小解锁条件

1. 将 QMAI 出站访问改为可注入、仅测试允许的本地 Mock 适配器，并在验收 profile 明确拒绝非 Mock 地址。
2. 以加密或受控密钥引用保存凭证/token，保留读取脱敏，并补齐配置、拒绝、查询和导出的审计。
3. 提供后端的营业额/商品销量查询与导出，逐租户、品牌、门店校验并覆盖匿名 401、跨范围 403。
4. 将配方计算与导出纳入服务端 BigDecimal 快照、权限范围及审计；增加独立 H2 合成数据测试和 1280px Chromium Mock 验收后再复验。

本次按 BLOCKED 停止，未进入后续用例或修改 G3 台账。

# STORE-01 每日报损及按月 Excel 数据导出验收报告

执行时间：2026-07-21（Asia/Shanghai）
执行范围：G3 / STORE-01；桌面 Web；**不包含照片包**；仅隔离 H2 与合成数据。

## 结论

**PASS**。日报损查询和按月 Excel 导出已统一收紧为仅 `BOSS`、`FINANCE`；菜单、路由守卫、默认模板、H2/MySQL 迁移及后端角色上限一致。`STORE_MANAGER`、`SUPERVISOR`、`WAREHOUSE`、`EMPLOYEE` 即使被误配报损读/导出权限仍为 403，匿名为 401。未进入 STORE-02。

## 已执行验证

| 项目 | 结果 | 证据摘要 |
| --- | --- | --- |
| 后端定向回归 | PASS | `DailyLossControllerTest` 4、`DailyLossServiceTest` 13、`AccessControlServiceTest` 23、`DailyLossReadExportAuthorizationMigrationTest` 1，合计 41 项通过。 |
| 后端全量测试 | PASS | Maven Surefire：172 个套件、830 项测试、0 failure、0 error、0 skipped。 |
| 后端打包 | PASS | `mvn -q -DskipTests package` 成功。 |
| 前端类型检查与生产构建 | PASS | `vue-tsc -b` 与 `npm run build` 成功。 |
| Chromium 与 1280px | PASS | 两套相关 Chromium 回归通过；BOSS/FINANCE 可访问并下载，四类受限角色无菜单/路由入口；店长、督导在故意注入遗留读/导出权限时仍进入无权限页；FINANCE 报损页在 1280×900 无横向溢出。 |

## 权限、数据范围与导出证据

- 后端 `requireDailyLossRead`、`requireDailyLossExport` 均先验证权限，再以角色上限限制为 BOSS/FINANCE；误配的四类角色得到 403，并按既有 `permission_denied` 规则写入拒绝审计。Controller 的匿名月度导出在服务调用前返回 401。
- V76（MySQL）与 V75（H2）移除店长、督导、仓库、员工及遗留运营角色的日报损读/导出模板授权，仅保留 FINANCE 模板的两项权限；受影响账户权限版本递增、会话失效。个人误配允许项不构成绕过后端角色上限的路径。
- BOSS/FINANCE 的合成 H2 月度导出按认证租户和 FINANCE 数据范围查询。跨店伪造请求在仓库查询前为 403；第二租户验证仅将其 `tenantId` 传入三类导出查询，不会回退至租户 1。
- `.xlsx` 仅有“每日汇总”“报损明细”两张工作表：31 日汇总、数量/金额数值单元格、筛选及冻结表头与合成数据一致。插入图片、CSV、PDF 附件后，工作簿无图片、绘图对象、附件名、附件 URL 或 ZIP；前端也无“导出照片包”入口。
- 两次相同下载保持报损单和明细数量不变，仅新增两条符合规则的 `daily_loss_export` 审计；审计内容限定月份、门店范围和行数，不含附件内容。

## 清理与基线

- 全程未读取或输出密钥，未连接生产或 QA MySQL；测试仅使用 JVM 内存 H2 与 Chromium mock API 合成数据。
- H2 JVM 结束后，临时业务数据、角色误配、会话和审计均已释放；所有 Chromium context 与本机静态预览均已关闭。
- 未创建持久化 QA 数据、账号、权限覆盖或审计，QA 基线未改写；工作区其他既有修改未处理。

## 剩余风险与下一动作

- 风险：本轮按允许范围使用隔离 H2；尚未在独立 QA MySQL 上做真实会话下载演练。该项不阻断本门禁，但后续发布候选环境应执行同一组回归。
- 下一动作：STORE-01 已通过；等待授权后再开始 STORE-02，本次不进入下一门禁。

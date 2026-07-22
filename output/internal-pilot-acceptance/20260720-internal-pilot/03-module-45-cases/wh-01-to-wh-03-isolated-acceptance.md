# WH-01～WH-03 仓储基础隔离验收报告

执行批次：`WH-01-03-H2-20260721`
结论：`PASS`（仅限本地隔离合成环境）

## 范围与隔离证明

- 范围仅包括 WH-01 物料档案、WH-02 总仓/区域仓库存、WH-03 门店库存；未进入 WH-04，也未修改 `g3-gate-ledger.md`。
- 后端验证使用测试生命周期创建的内存 H2 空库和合成测试数据；共应用 62 条迁移。未读取 `.env.qa` 的值、未连接 QA MySQL、现有 Docker 容器、局域网或生产环境。
- 前端验证使用本机 Vite 与 Playwright 的接口拦截数据；未使用真实会话、账号或业务数据。验收后临时 Vite 端口 `18173` 已关闭。

## 验收证据

### WH-01 物料档案

- 正常：仓管与老板可新建分类/物料；分类、单位、部门和可选档案字段可保存；并发创建不同物料正常完成。
- 异常：同物料编码并发保存不会产生重复档案；物料停用后不再向店长可用清单暴露，使用该物料入库返回 `ITEM_NOT_FOUND`。
- 角色与审计：店长即使被显式赋予中心仓权限，仍不能执行物料及中心仓写操作；并发物料操作的 `operation_log` 断言为预期的两条审计记录。

### WH-02 总仓/区域仓库存

- 正常：仓管可设置最低库存预警；总仓与区域仓标识在清单范围内保持独立，当前仓读取权限可打开对应仓库清单。
- 异常与范围：个人读取拒绝会在仓库查询前中止；当前仓读取拒绝不能被历史读取许可绕过；店长即使被错误授予中心仓操作许可仍被拒绝。
- 页面：桌面 Chromium 的总仓、区域仓和权限范围回归 3/3 通过；截图 `output/playwright/warehouse-central-workbench.png` 为 1280×720，人工核验无可见横向溢出。

### WH-03 门店库存

- 正常与范围：门店或中心仓读取权限按对应工作台判定；门店清单只为已授权门店执行范围查询，不走全局查询；门店库存概览不会读取中心仓供应商、采购或批次数据。
- 异常：个人门店读取拒绝会在任何仓库查询前终止，防止通过后续查询绕过数据范围。
- 回归：上述门店范围断言与后端全量回归共同通过。

## 执行结果

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 仓储定向 H2 测试 | PASS | 13/13，通过物料、中心/区域仓、门店范围、拒绝与审计断言 |
| 后端全量测试 | PASS | 830/830，0 failure、0 error |
| 后端打包 | PASS | `mvn -q -DskipTests package` |
| 前端类型检查与生产构建 | PASS | `npm run build`（含 `vue-tsc -b` 与 Vite build） |
| 1280px Chromium 仓库回归 | PASS | 3/3；本地接口拦截式测试，截图为 1280×720 |
| 清理 | PASS | 内存 H2 及合成数据随测试生命周期释放；本地 Vite 已停止；未写入 QA 基线 |

## 命令摘要

```text
mvn -q -Dtest='<13 个 WH-01～WH-03 定向方法>' test
E2E_BASE_URL=http://127.0.0.1:18173 npx playwright test \
  tests/e2e/18-warehouse-network-ui.spec.ts --project=chromium \
  --grep 'central warehouse entry|central warehouse without pending work|multi-warehouse admin switches'
mvn -q test
mvn -q -DskipTests package
npm run build
```

## 残留风险与下一步

- 本次未使用独立 QA MySQL 或真实登录会话；因此结论仅覆盖本地隔离 H2 与接口拦截式桌面回归，不能替代后续已授权的 QA MySQL 端到端演练。
- 本轮无失败项，故 WH-01～WH-03 可标记为隔离验收通过；按要求不进入 WH-04，等待主线程复核与下一项明确授权。

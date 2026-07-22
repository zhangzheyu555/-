# G1 构建、迁移与基础安全台账

执行批次：`E2E-20260720`
执行时间：2026-07-20（本机隔离 QA）
范围：桌面 Web；不包含移动端。

## 当前结论

`G1 PASS（V74 候选复核）`。已重新完成构建、空库与升级迁移、服务入口、401/403 基线及候选产物敏感信息扫描；可恢复 G3。

## 已验证证据

| 编号 | 结果 | 证据 / 结论 |
| --- | --- | --- |
| G1-01 后端全量测试 | PASS | Maven Surefire 共 770 项测试，`failures=0`、`errors=0`、`skipped=0`；包含叫货默认值迁移、无默认值 Repository 写入、角色待办中文错误和跨门店拒绝统一审计边界回归。 |
| G1-02 后端候选包 | PASS | `mvn -q -DskipTests package` 成功；JAR SHA-256：`b00dc7b93bbe833635a3596c25106c603d1bf36d2af70b2730ad859ce63c3c7d`。 |
| G1-03 前端类型与生产构建 | PASS | Docker 候选构建复用 `npm run build`（先执行 `vue-tsc -b` 再执行 Vite）；前端入口 SHA-256 `c3d86e9cb969e60a34d0fc1b6a8a4197d02f8bc812b2cf1adebe8bd3935d3318`。 |
| G1-04 空 QA MySQL 迁移 | PASS | 独立库 `ai_profit_dev_qa_flyway_empty_v74_20260720` 从空库启动候选应用；Flyway 最终 `V74`，102 张表，失败迁移 0；`store_requisition.version` 默认 0，`store_requisition_line.shipped_quantity` 默认 0.00。 |
| G1-04 升级迁移 | PASS | 原 V71→V72 的 10 项角色迁移断言继续保留；运行 QA 库从 V72 顺序升级到 V73/V74，失败迁移 0。升级后店长真实叫货 API 返回 200，表头版本 0、明细已发数量 0，同幂等键重复提交仍只有 1 表头、1 明细、1 审计和 1 幂等记录；测试数据已清理。 |
| G1-05 服务与入口 | PASS | Docker Compose 的 backend/frontend/gateway/mysql/redis/postgres/inspection-service/qmai-mock 均 healthy；网关 `/healthz`、网关 API、前端 health/admin、后端 API health 均 HTTP 200。 |
| G1-06 未登录基线 | PASS | 27 个一级业务 API 域（财务、门店、仓库、巡检、员工、培训、平台、导入导出等）未携带令牌均返回 HTTP 401。 |
| G1-06 最小权限写拒绝 | PASS | 修正 DENY 夹具为 58 项显式 DENY、0 有效权限、默认 `/no-permission` 后，对财务、报销、仓库、日报损、巡检、员工、工资、QMAI、门店、账号、培训及导入的真实写接口复测均为 HTTP 403。仓库合法最小调拨请求被拒绝且数据库 `idempotency_key=E2E-20260720-DENY-G1-WH` 记录数为 0。 |
| G1-07 候选产物扫描 | PASS | 对 V74 后端 JAR 与前端 `dist` 重新扫描，未发现 QA 测试账号、E2E 标识、密码变量、私钥、Token 或 API Key 明文匹配项。 |
| G1-08 远程默认分支复核 | PASS | 默认分支已快进到 `f5e3796cb0504722f268e0501998f152cb5b4b55`，未新建分支、未强推；Release CI 的源码卫生、后端测试与打包、Vue 类型检查/依赖审计/生产构建、MySQL 8/Flyway 空库启动均通过。MySQL 任务确认 74 个迁移、最新 V74、应用健康 UP。移动端任务按本批次明确范围排除，不作为桌面 Web 门禁。 |

## 环境修复记录

- 首次隔离迁移误用了 `.env.qa` 中与当前开发编排不一致的数据库账号，连接被拒绝；改为在运行中的后端容器内使用已有受控应用凭据完成迁移，未读取或写出任何密码。
- 初始 DENY 账号误保留督导模板权限，导致 QMAI 探测可被调用。已通过老板正式授权 API 写入全量显式 DENY 并使旧会话失效；复测后 QMAI 及其他写接口均为 403。
- 仓库调拨提交对不存在单据会先返回 404，培训错题路径传入非数值会先返回 400；这两者不能作为授权证据。已改用合法最小的创建/数值请求，确认授权拒绝发生在写入前。
- 远程 MySQL 8.0.46 默认空库使用 `utf8mb4_0900_ai_ci`，历史 V40 临时表与既有 `utf8mb4_unicode_ci` 表比较时失败。CI 验证脚本现只接受安全数据库名，并在受控空库执行 Flyway 前统一数据库排序规则；本机复现验证排序规则由 `utf8mb4_0900_ai_ci` 转为 `utf8mb4_unicode_ci`，V1→V74 和健康检查通过，远程 MySQL 门禁随后通过。

## G2 放行所需下一步

V74 变更未扩大角色权限；六角色对 WS-01 至 WS-05 的补充矩阵和匿名 401 已复核。WS-06 的后续代码修复已重新执行全量测试、打包、敏感扫描、部署、接口和审计回归，G3 可从 WS-07 员工工作台继续。

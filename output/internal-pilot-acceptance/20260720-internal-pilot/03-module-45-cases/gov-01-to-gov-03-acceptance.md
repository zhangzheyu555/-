# GOV-01～GOV-03 隔离验收报告

- 验收时间：2026-07-21（本机时区）
- 验收范围：桌面 Web；GOV-01 账号、角色、权限码与数据范围，GOV-02 操作日志与审计查询，GOV-03 组织、品牌、门店与平台级配置。
- 状态：**PASS**
- 环境边界：仅使用进程内 H2、MockMvc 与 Chromium API Mock。未连接 QA MySQL、Docker、局域网或生产环境；未读取或输出密钥；未测试移动端。

## 执行结果

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| GOV 后端定向 | PASS，87/87 | `UserAuthorizationManagementTest`、`UserManagementServiceTest`、`UserControllerAuthorizationContractTest`、`AccessControlServiceTest`、`AuthorizationServiceTest`、`DataScopeServiceTest`、`TokenPermissionVersionIntegrationTest`、`AuditControllerTest`、`AuditRepositoryTransactionPolicyTest`、`OrganizationServicePermissionTest`、`OrganizationDataScopeRepositoryTest`、`StoreControllerHttpAuthorizationTest`、`PlatformStatusControllerDataScopeTest`、`PlatformAdapterTest`；0 failure / 0 error / 0 skipped。 |
| H2/Flyway 权限版本 | PASS | `TokenPermissionVersionIntegrationTest` 在进程内 H2 执行 Flyway；权限版本递增后旧令牌无法再解析。 |
| 桌面 Chromium Mock | PASS，8/8 | `14-permission-architecture.spec.ts` 5/5；`34-store-management.spec.ts` 3/3，后者以 1280px 视口验证门店管理、新增、状态筛选、权限拦截和无整页横向溢出。 |
| 运行资源清理 | PASS | 临时 Vite 仅绑定 `127.0.0.1:18176`，回归后已停止；H2 为内存库，随测试退出释放。 |

> 本轮按 GOV 隔离委托只执行定向测试，未执行后端全量、后端打包、`vue-tsc -b` 或生产构建；这些全局验证由主线程统一安排，不能以本报告替代。

## GOV-01：账号、角色、权限码与数据范围

- 账号与权限管理由 BOSS 硬上限保护；普通角色即使存在个人 `ALLOW` 也不能维护账号权限。前端账号权限页同样仅对 BOSS 展示管理操作，后端仍为最终授权边界。
- 正式角色收敛为 BOSS、FINANCE、WAREHOUSE、STORE_MANAGER、SUPERVISOR、EMPLOYEE；`ADMIN`/`OWNER` 归一为 BOSS，`OPS`/`OPERATIONS` 归一为 SUPERVISOR。督导可获得受控的平台/考试等运营能力，但不能取得账号管理、门店管理或财务敏感写入。
- BOSS 授权固定；非 BOSS 的门店管理、财务导入等高风险权限不能由历史模板或个人覆盖突破。店长只能使用绑定门店范围，督导在无分配时采用保守范围，且其平台范围不能配置为 `ALL`。
- 授权更新替换个人覆盖和数据范围后递增权限版本、清除旧令牌，并记录授权变更的前后快照。匿名账号管理接口为 401，错误角色为 403；权限拒绝写入既有拒绝审计。

## GOV-02：操作日志与审计查询

- 审计查询是只读接口，先从认证上下文取得当前用户，再执行审计读取授权；匿名为 401、无审计权限为 403，拒绝时不会访问日志仓储。
- `operation_log` 查询与写入都以认证用户的 `tenant_id` 为边界；日志写入的操作人来自认证上下文，不接受客户端伪造的操作人或租户字段。
- 账号授权成功、门店变更成功及关键拒绝路径均复用统一审计仓储；密码重置审计不记录原密码、新密码或哈希。

## GOV-03：组织、品牌、门店与平台级配置

- 组织 H2 断言覆盖品牌/门店按 `tenant_id` 与授权门店范围过滤、空范围返回空集、跨租户门店 ID 返回 403 且记录拒绝审计。
- 新增门店、重复编码、非法状态、错误品牌、已引用门店删除保护均有 H2/MockMvc 断言；成功变更写审计，失败不产生半写入。BOSS 可写；店长、督导、仓库、员工、财务即使会话声称 `store.manage` 仍无页面入口且后端为 403；匿名写入为 401。
- 平台状态读取要求平台读取权限及非空平台范围；平台配置的读取/保存端点从认证用户取得租户、要求平台管理权限，并仅返回脱敏配置视图。督导的平台能力仍受其受授权范围约束，BOSS 具有全域治理能力。

## 桌面 UI 证据

- Chromium API Mock 未发送任何后端请求：所有 `/api/**` 均由测试路由拦截并返回合成数据。
- 1280px 门店管理回归通过：BOSS 可新增、按“全部/启用/停用”真实筛选并在启停后同步列表；重复编号、非法区域展示既有中文业务错误且 Mock 数据不变；五类非 BOSS 角色伪造 `store.manage` 后仍无新增入口。
- 权限架构回归通过：会话含权限、范围、默认工作台、权限版本；前端将 401 与 403 分别处理，账号授权采用统一访问档案更新契约。

## 清理与残留风险

- H2、Mock 浏览器上下文与临时 Vite 均已清理；未写入 QA 基线、业务数据、会话或持久化测试审计。
- 未执行移动端，符合本轮“仅桌面 Web”范围。
- QMAI-04 配方销量/用量流程已由主线程按缺少权威配方数据决定暂缓；不属于本 GOV 结论，也不影响本报告的治理权限与平台配置边界验证。
- 未修改 G3 台账，未提交代码。

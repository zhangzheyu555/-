# AI Profit OS V2 多租户底座设计

日期：2026-07-07

## 背景

当前系统已经从纯前端 Demo 迁移到 Spring Boot 3、Java 21、MySQL、Flyway 的后端主线，并完成了 8080 旧版页面的数据助手 DeepSeek 接入。现有功能能支撑单个企业查看门店、录入利润、查询报表、使用 AI 助手，但系统数据模型仍然是单企业结构。

V2 产品定位已经升级为“AI 企业经营分析系统”，未来要服务多家企业。第一阶段选择“商业 Demo 多租户底座”：在不重写现有业务和前端的前提下，把企业隔离边界补进数据库、认证和核心查询。

## 目标

本阶段目标是让当前系统具备最小可用的多企业承载能力：

- 新增企业租户模型。
- 将现有数据迁入一个默认企业。
- 让登录用户具备明确的 `tenant_id`。
- 所有核心经营数据查询和写入都按当前用户的 `tenant_id` 隔离。
- 保持现有 8080 页面、`admin / 123` 登录、利润看板、门店管理和 DeepSeek 助手继续可用。
- 为未来企业开通、RBAC 数据权限、AI Agent、Excel 导入中心预留清晰边界。

## 非目标

本阶段不做以下内容：

- 不做完整 SaaS 注册、企业开通、套餐计费。
- 不做 Vue 前端重构。
- 不重写旧版 8080 页面。
- 不做复杂组织层级、区域经理、门店授权 UI。
- 不做完整 AI Agent 中心。
- 不把 DeepSeek Key、MySQL 密码或其他密钥写入代码、配置文件或前端。

## 现状概览

当前 Flyway 迁移中已经有这些核心表：

- `auth_user`
- `auth_token`
- `role_permission`
- `user_store_scope`
- `brand`
- `store_branch`
- `profit_entry`
- `salary_record`
- `expense_claim`
- `inspection_record`
- `platform_account`
- `operation_log`
- `kv_storage`

当前代码按模块拆分：

- `platform/auth`：登录、Token、当前用户。
- `organization`：品牌、门店。
- `finance`：利润录入、看板、月份数据。
- `assistant`：本地规则回答和 DeepSeek 分析。
- `audit`、`operations`、`people`、`reporting`：围绕经营管理的周边模块。

当前主要缺口：

- 表没有 `tenant_id`，不同企业数据无法隔离。
- `auth_user.username` 是全局唯一，未来不同企业不能使用相同账号名。
- `brand.code`、`brand.name`、`store_branch.code` 等唯一约束是全局唯一，不适合多企业。
- 查询没有租户过滤，一旦多企业数据进入数据库，会发生串数据风险。
- 操作日志没有企业维度，后续审计追踪不完整。

## 方案比较

### 方案一：新建 V2 全量表并重写业务

优点是模型最干净，可以直接按 SaaS 产品重新设计。缺点是会打断当前已验证的 8080 主线，需要重写大量查询、页面适配和数据迁移，短期风险高。

### 方案二：兼容式多租户改造

在现有表上增加 `tenant_id`，创建默认企业，将当前数据归属到默认企业，然后逐个 repository 加租户过滤。这个方案最适合当前阶段：保留已有业务能力，同时把未来卖给多企业客户所需的数据隔离立起来。

### 方案三：并行 V2 表加适配层

保留旧表，同时新增 `tenant`、`profit_daily`、`cost_record` 等 V2 表，通过适配层同步数据。优点是未来模型更接近产品架构文档，缺点是双写、同步、回滚和排错复杂度过高，不适合作为第一阶段。

推荐选择方案二。

## 设计方案

### 1. 企业租户模型

新增 `tenant` 表：

```sql
tenant(
  id bigint primary key auto_increment,
  name varchar(160) not null,
  industry varchar(80) null,
  scale varchar(80) null,
  status varchar(40) not null default 'ACTIVE',
  created_at timestamp not null default current_timestamp,
  updated_at timestamp null default null
)
```

创建默认企业：

- `id = 1`
- `name = '默认企业'`
- `industry = 'chain_store'`
- `status = 'ACTIVE'`

现有数据全部迁入这个默认企业。

### 2. 核心表增加租户字段

以下表新增 `tenant_id bigint not null default 1`：

- `auth_user`
- `auth_token`
- `role_permission`
- `user_store_scope`
- `brand`
- `store_branch`
- `profit_entry`
- `salary_record`
- `expense_claim`
- `inspection_record`
- `platform_account`
- `operation_log`

`kv_storage` 暂不强制租户化。它仍然作为历史兼容 KV 层保留，后续如果要承载企业级配置，再拆为 `tenant_setting`。

### 3. 唯一约束调整

多租户后，原来的全局唯一约束要改为企业内唯一：

- `auth_user.username` 改为 `(tenant_id, username)` 唯一。
- `brand.code` 改为 `(tenant_id, code)` 唯一。
- `brand.name` 改为 `(tenant_id, name)` 唯一。
- `store_branch.code` 改为 `(tenant_id, code)` 唯一。
- `profit_entry.store_id + month` 维持唯一，同时增加 `tenant_id` 后建议改为 `(tenant_id, store_id, month)`。
- `user_store_scope.user_id + store_id` 建议改为 `(tenant_id, user_id, store_id)`。
- `role_permission.role_code + permission_code` 建议改为 `(tenant_id, role_code, permission_code)`。

外键仍保留到原主表，但业务查询必须同时校验 `tenant_id`。

### 4. 当前用户与租户上下文

`AuthUser` 增加 `tenantId` 字段。登录和 Token 校验时返回用户所属企业。

新增轻量租户访问方式：

- `AuthService.requireUser(authorization)` 返回带 `tenantId` 的用户。
- Controller 从当前用户取 `tenantId`，传入 Service。
- Service 将 `tenantId` 传入 Repository。
- Repository 的 SQL 明确加 `tenant_id = ?`。

本阶段不引入复杂线程上下文或全局拦截器，避免隐式状态让旧模块排错变难。等 API 规范化阶段再考虑统一 `TenantContext` 或请求拦截器。

### 5. 认证与会话

`AuthRepository.findByUsername` 在本阶段仍按用户名登录，但只查默认企业或唯一匹配用户。商业 Demo 暂不支持同一账号名跨企业登录选择。

后续 SaaS 阶段可以扩展为：

- 企业编码 + 用户名登录。
- 手机号登录后选择企业。
- 独立企业域名。

`LoginResponse` / `SessionUser` 可以增加 `tenantId` 和 `tenantName`，旧前端即使不用也不会影响现有页面。

### 6. 组织与经营数据隔离

`OrganizationRepository` 所有查询和写入加租户参数：

- 品牌列表只返回当前企业品牌。
- 门店列表只返回当前企业门店。
- 新增或更新品牌、门店时写入当前企业 `tenant_id`。
- 门店授权 `user_store_scope` 只能绑定同企业用户和同企业门店。

`FinanceRepository` 所有查询和写入加租户参数：

- 利润列表按 `p.tenant_id` 和 `s.tenant_id` 过滤。
- 可用月份只统计当前企业数据。
- 门店存在性检查只检查当前企业。
- 利润 upsert 写入当前企业 `tenant_id`。
- 操作日志写入当前企业 `tenant_id`。

`AssistantService` 继续支持前端传入可见数据上下文。若走后端数据库上下文，也必须只使用当前用户 `tenant_id` 范围内数据。

### 7. 数据迁移策略

使用新的 Flyway 脚本，例如：

```text
V3__tenant_foundation.sql
```

迁移顺序：

1. 创建 `tenant` 表。
2. 插入默认企业。
3. 给核心表增加 `tenant_id`，默认值为 1。
4. 回填已有记录的 `tenant_id = 1`。
5. 增加租户索引。
6. 调整唯一约束。

MySQL 删除旧唯一索引时要使用实际索引名。当前脚本中的索引名包括：

- `username` 可能是 `auth_user` 的自动唯一索引名。
- `code`、`name` 可能是 `brand` 的自动唯一索引名。
- `uk_store_code`
- `uk_profit_store_month`
- `uk_user_store_scope`
- `uk_role_permission`

实施时需要先用 `information_schema.statistics` 或 `show index` 确认索引名，避免迁移脚本在不同数据库上失败。

### 8. 兼容性策略

当前 8080 页面不需要感知租户。它继续：

- 用 `admin / 123` 登录。
- 请求已有 API。
- 使用已有本地规则助手和 DeepSeek 分析。

后端负责把 `admin` 用户映射到默认企业，并在接口层自动隔离数据。

### 9. 错误处理

租户相关错误使用明确业务错误：

- 当前用户没有 `tenant_id`：返回未授权或系统配置错误。
- 查询门店不属于当前企业：返回 404 或权限不足，不暴露其他企业门店是否存在。
- 写入利润时门店不存在于当前企业：拒绝写入。
- 登录账号存在但企业停用：拒绝登录。

### 10. 测试计划

代码级验证：

- 运行 `mvn -q -DskipTests package`。
- 检查 Flyway 迁移能在已有数据库上执行。
- 检查新增 SQL 都带租户过滤。

接口验证：

- `GET /api/health` 正常。
- `POST /api/auth/login` 使用 `admin / 123` 成功。
- 会话返回用户信息中包含企业信息。
- 品牌、门店、利润、月份接口仍返回默认企业数据。
- `/api/assistant/chat` 仍能返回本地答案，配置 DeepSeek 时返回 AI 分析。

隔离验证：

- 手工插入第二企业、第二企业门店和利润数据。
- 默认企业用户查询不到第二企业数据。
- 默认企业用户不能向第二企业门店写入利润。
- 可用月份只来自当前企业。

浏览器验证：

- 打开 `http://127.0.0.1:8080/index.html`。
- 登录管理员。
- 看板、门店、利润录入页面仍可使用。
- 问“保利店营业额”仍显示默认企业的最新月份数据，并可追加 DeepSeek 分析。

### 11. 风险与缓解

风险一：Flyway 修改唯一索引在本机数据库和其他环境索引名不一致。

缓解：实施前用数据库元数据确认索引名；迁移脚本尽量写成可重复、可检查的形式。

风险二：遗漏某个查询的租户过滤，导致串数据。

缓解：优先改 repository 层，所有 `select`、`insert`、`update`、`exists` 方法都显式携带 `tenantId`；用第二企业测试数据验证。

风险三：旧前端仍有本地数据或兼容 KV 路径，可能绕过后端隔离。

缓解：本阶段只承诺后端 API 的企业隔离；旧前端本地兼容代码不作为企业级数据边界。后续 API 规范化阶段继续收口前端数据访问。

风险四：AI 助手如果使用前端传入上下文，理论上取决于前端当前可见数据。

缓解：后端数据库上下文必须租户过滤；前端可见上下文只来自当前页面数据。后续企业版应让 AI 助手统一走后端数据权限层。

## 实施顺序

1. 新增 Flyway `V3__tenant_foundation.sql`，创建租户和回填默认企业。
2. 修改认证模型：`AuthUser`、`SessionUser`、`LoginResponse` 支持企业信息。
3. 修改 `AuthRepository`，登录、Token、用户列表、默认管理员创建都带租户。
4. 修改组织模块 repository/service/controller，把 `tenantId` 传入查询和写入。
5. 修改财务模块 repository/service/controller，把 `tenantId` 传入查询和写入。
6. 修改审计、平台账号、用户管理等边缘模块，补租户字段和过滤。
7. 检查 `AssistantService` 数据库上下文路径，确保按租户读取。
8. 构建、接口测试、隔离测试、浏览器回归。

## 成功标准

本阶段完成后，系统仍像现在一样可用，但数据库和后端已经具备企业边界。它还不是完整 SaaS，却已经从“单企业 Demo”变成“可继续演进为多企业商业产品的底座”。

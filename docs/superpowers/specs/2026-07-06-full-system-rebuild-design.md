# 门店利润系统全面重构设计

日期：2026-07-06

## 1. 结论

本次重构采用“全面重构 + 模块切片迁移”的路线。目标不是继续在现有单页前端上补功能，而是把系统改造成可长期使用、可多人协作、可审计、可维护的门店经营管理系统。

已确认的方向：

- 重构力度：全面重构。
- 前端形态：分组工作台。
- 视觉风格：专业密集型后台。
- 后端架构：领域模块化单体。
- 实施路线：新系统骨架先搭建，再按模块切片迁移。

核心原则：

- 后端是唯一可信数据源。
- 权限、审计、利润计算、审批流、数据助手边界必须在后端执行。
- 前端负责展示、交互、录入体验和轻量导出触发。
- 旧系统数据先备份，再迁移，迁移结果必须可对账。
- 每个阶段都要保持系统可运行、可验收、可回退。

## 2. 当前系统评估

当前项目已经有 Java 后端，但后端主要承担兼容存储和数据助手入口：

- `backend/`：Spring Boot 3.3.5、Java 21、JDBC、Flyway、MySQL。
- `/api/storage`：把前端 JSON 写入 `kv_storage`。
- `/api/assistant/chat`：DeepSeek 数据助手接口。
- `index.html`：现有前端 UI、业务逻辑、权限控制、模块渲染集中在一个大文件。
- `database.js`：种子数据、账号、存储、CloudBase 兼容逻辑集中在一个大文件。

当前主要问题：

- 前端文件过大，业务逻辑、渲染、权限、数据访问耦合严重。
- MySQL 当前主要保存 JSON，业务表尚未成为主数据源。
- 登录和权限仍以浏览器侧逻辑为主，不能真正防止越权。
- 利润计算、工资结转、报销审批、巡店记录、操作日志没有统一后端规则。
- 图片仍以 base64 / 本地兼容逻辑为主，不适合长期保存。
- 数据助手依赖前端拼接数据上下文，可靠性和安全边界不足。
- 页面体验已经能用，但模块多后导航、筛选、表格、审批和录入效率需要重新设计。

## 3. 目标和非目标

### 3.1 目标

1. 建立正式的 Java + MySQL 前后端分离架构。
2. 按业务域拆分后端模块，使每个模块职责清晰。
3. 重建前端为专业密集型经营后台，支持长期办公使用。
4. 将门店、品牌、利润、工资、报销、巡店、日志、账号权限迁移到业务表。
5. 后端统一利润计算和报表口径。
6. 后端统一权限校验和数据范围控制。
7. 所有写操作进入操作日志。
8. 数据助手只回答系统内问题，优先基于 MySQL 查询结果回答。
9. 保留旧 JSON 数据作为迁移来源和回退备份。
10. 每个阶段都有明确验收标准。

### 3.2 非目标

1. 不做微服务拆分。
2. 不引入复杂工作流引擎。
3. 不做多租户 SaaS 化。
4. 不做原生移动 App。
5. 不在第一阶段追求所有导出都由后端生成。
6. 不把 AI 作为业务数据计算来源，AI 只能解释和润色后端查询结果。

## 4. 总体架构

```text
frontend/ or web/
  Vue 3 + TypeScript + Vite
  grouped workbench layout
  module pages
  API client
  role-aware UI

backend/
  Spring Boot
  domain modules
  REST API
  permission and audit
  migration services
  file storage

MySQL
  business tables
  Flyway migrations
  kv_storage as legacy backup
```

请求链路：

```text
User
  -> Frontend workbench
  -> API client
  -> Spring Boot controller
  -> Module service
  -> Permission/data-scope check
  -> Repository/JDBC
  -> MySQL
  -> Audit log for write operations
```

## 5. 前端设计

### 5.1 技术方向

建议新前端使用：

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Axios 或 Fetch 封装
- ECharts，用于经营图表

选择理由：

- 当前系统是单页后台，Vue 适合快速拆模块和维护状态。
- TypeScript 能约束后端 DTO 和前端表格字段。
- Vite 启动快，适合本地迭代。
- Pinia 用于保存当前用户、角色、门店范围、全局筛选。

旧 `index.html` 在过渡期保留为 legacy 页面，不作为新功能主线继续扩展。

### 5.2 信息架构

采用“分组工作台”。

左侧一级分组：

1. 经营驾驶舱
2. 数据中心
3. 门店运营
4. 系统管理

每个分组下设置二级模块：

#### 经营驾驶舱

- 利润概览
- 利润表
- 门店详情
- 排名分析
- 亏损预警
- 数据助手

#### 数据中心

- 利润录入
- 员工工资
- 报销数据
- 巡店记录
- 数据导入
- 数据导出

#### 门店运营

- 门店档案
- 报销审批
- 督导巡店
- 平台登录资料
- 巡检标准

#### 系统管理

- 用户权限
- 操作日志
- 基础配置
- 数据迁移
- 系统健康

### 5.3 视觉风格

采用“专业密集型后台”。

设计规则：

- 背景以中性灰白为主。
- 橙色保留为品牌主色，用于主操作、当前选中状态和关键引导。
- 红色用于亏损、拒绝、删除、风险。
- 绿色用于盈利、通过、完成。
- 蓝色用于信息、链接、普通状态。
- 卡片边角保持克制，避免过度圆角和装饰。
- 页面首屏优先展示筛选器、KPI、表格、异常提示。
- 表格信息密度高于当前版本，适合财务核对和长期录入。
- 移动端以查看、审批、轻量录入为主，不强行承载复杂表格。

### 5.4 关键组件

需要建立统一组件：

- AppShell：整体布局、侧边栏、顶部用户区。
- ModuleHeader：模块标题、说明、主操作。
- FilterBar：月份、品牌、门店、状态、关键词筛选。
- DataTable：排序、分页、固定列、空状态、加载状态。
- MetricCard：经营指标卡。
- StatusTag：待审核、已完成、营业中、停业、亏损等状态。
- EditDrawer：新增/编辑抽屉。
- ConfirmDialog：删除、审批、撤销确认。
- DetailPanel：门店详情、报销详情、巡店详情。
- UploadField：图片和表格上传。
- DataAssistantPanel：数据助手聊天和结构化结果。

### 5.5 前端模块迁移方式

新前端不直接复用旧 `index.html` 的渲染函数。迁移方式是：

1. 从旧前端抽取字段、业务规则和展示口径。
2. 在新前端中按模块重写页面。
3. 通过后端 API 获取数据。
4. 对照旧页面关键指标，确认结果一致。
5. 对应模块验收通过后，旧页面该模块停止扩展。

## 6. 后端设计

### 6.1 架构模式

采用“领域模块化单体”。

仍然是一个 Spring Boot 应用，但内部按业务域拆包：

```text
com.storeprofit.system
  platform
    auth
    security
    config
    health
  organization
    brand
    store
    platformaccount
  finance
    profit
    report
    expense
    export
  people
    salary
  operations
    inspection
    standard
    file
  assistant
  audit
  migration
  common
```

每个业务模块内部统一结构：

```text
module/
  ModuleController.java
  ModuleService.java
  ModuleRepository.java
  dto/
  model/
```

当前后端已使用 JDBC。全面重构阶段继续使用 `JdbcTemplate` / `NamedParameterJdbcTemplate`，避免为了重构再引入 ORM 复杂度。若后续实体关系变复杂，再评估是否引入 jOOQ 或 MyBatis。

### 6.2 公共后端能力

必须先建设公共能力：

- 统一响应格式。
- 统一异常处理。
- 参数校验。
- 分页查询模型。
- 排序和筛选模型。
- 当前登录用户上下文。
- 角色权限判断。
- 门店数据范围判断。
- 操作日志记录。
- 文件上传与访问。
- 数据迁移执行记录。
- API 错误码。

统一错误格式：

```json
{
  "success": false,
  "code": "FORBIDDEN",
  "message": "无权限操作该门店数据",
  "data": null
}
```

统一成功格式：

```json
{
  "success": true,
  "code": "OK",
  "message": "OK",
  "data": {}
}
```

### 6.3 权限设计

角色：

- `ADMIN`：系统管理员，全部权限。
- `BOSS`：老板，全门店经营数据只读，允许导出。
- `FINANCE`：财务，可录入利润、处理报销、导入导出，不能管理系统账号。
- `SUPERVISOR`：督导，可维护巡店记录和查看相关门店数据。
- `STORE_MANAGER`：店长，只能查看和提交自己门店相关数据。

权限执行规则：

- 前端只负责隐藏按钮和优化体验。
- 后端必须对所有接口做权限校验。
- 写接口必须记录操作日志。
- 店长、督导等角色必须按绑定门店或授权门店限制数据范围。
- 导出接口也必须受权限限制。

### 6.4 登录方式

第一版使用后端 token：

- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`

登录返回：

- token
- 用户信息
- 角色
- 可访问门店列表
- 菜单权限

密码必须后端哈希存储。旧系统中的明文密码只作为迁移来源，迁移后必须转换为哈希。

## 7. 数据模型

当前 `V1__init_schema.sql` 已创建基础业务表。本次全面重构将以这些表为基础继续演进，不推倒重建数据库。

### 7.1 保留并演进的表

- `kv_storage`：旧 JSON 备份和迁移来源。
- `auth_user`：用户账号。
- `brand`：品牌。
- `store_branch`：门店。
- `profit_entry`：月度利润录入。
- `salary_record`：员工工资。
- `expense_claim`：报销。
- `inspection_record`：巡店记录。
- `operation_log`：操作日志。

### 7.2 新增或增强的表

#### role_permission

保存角色权限。

字段：

- `id`
- `role_code`
- `permission_code`
- `created_at`

#### user_store_scope

保存用户可访问门店范围。

字段：

- `id`
- `user_id`
- `store_id`
- `created_at`

#### platform_account

保存平台登录资料。

字段：

- `id`
- `store_id`
- `platform_name`
- `login_url`
- `username`
- `password_cipher`
- `note`
- `created_at`
- `updated_at`

密码字段必须加密或至少避免明文返回给无权限用户。第一版可后端保存密文，前端只允许授权角色查看。

#### file_asset

保存上传文件。

字段：

- `id`
- `biz_type`
- `biz_id`
- `original_name`
- `storage_path`
- `content_type`
- `size_bytes`
- `uploaded_by`
- `created_at`

报销截图、巡店照片、导入文件都通过此表关联。

#### migration_run

保存迁移执行记录。

字段：

- `id`
- `migration_code`
- `source_key`
- `status`
- `summary_json`
- `started_at`
- `finished_at`

#### inspection_standard

保存巡检标准版本。

字段：

- `id`
- `brand_id`
- `name`
- `version`
- `standard_json`
- `enabled`
- `created_at`
- `updated_at`

第一版巡检标准可以 JSON 存储，后续根据稳定程度再拆分条款表。

### 7.3 利润计算口径

后端统一计算：

```text
income = sales - refund - discount
cost_sum = material + packaging + loss + cost_other
gross = income - cost_sum
expense_sum = rent + labor + utility + property + commission + promo + repair + equip + exp_other
net = gross - expense_sum
gross_margin = income > 0 ? gross / income : 0
net_margin = income > 0 ? net / income : 0
```

业务表只保存原始录入项，计算项默认不落库。报表接口返回计算结果。

## 8. API 设计

### 8.1 Platform

```http
POST /api/auth/login
GET  /api/auth/me
POST /api/auth/logout
GET  /api/system/health
```

### 8.2 Organization

```http
GET    /api/brands
POST   /api/brands
PUT    /api/brands/{id}

GET    /api/stores
GET    /api/stores/{id}
POST   /api/stores
PUT    /api/stores/{id}
PATCH  /api/stores/{id}/status

GET    /api/platform-accounts
POST   /api/platform-accounts
PUT    /api/platform-accounts/{id}
DELETE /api/platform-accounts/{id}
```

### 8.3 Finance

```http
GET    /api/profit-entries?month=2026-07&brandId=&storeId=
GET    /api/profit-entries/{storeId}/{month}
PUT    /api/profit-entries/{storeId}/{month}
DELETE /api/profit-entries/{storeId}/{month}

GET    /api/reports/dashboard?month=2026-07
GET    /api/reports/ranking?month=2026-07&metric=net
GET    /api/reports/stores/{storeId}?from=2026-01&to=2026-07
GET    /api/reports/profit-sheet?storeId=&month=
```

### 8.4 Expense

```http
GET    /api/expenses?month=&storeId=&status=
GET    /api/expenses/{id}
POST   /api/expenses
PUT    /api/expenses/{id}
POST   /api/expenses/{id}/approve
POST   /api/expenses/{id}/reject
DELETE /api/expenses/{id}
```

### 8.5 People

```http
GET    /api/salaries?month=&storeId=&keyword=
GET    /api/salaries/{id}
POST   /api/salaries
PUT    /api/salaries/{id}
DELETE /api/salaries/{id}
POST   /api/salaries/carry-forward
POST   /api/salaries/import
```

### 8.6 Operations

```http
GET    /api/inspections?month=&brandId=&storeId=
GET    /api/inspections/{id}
POST   /api/inspections
PUT    /api/inspections/{id}
DELETE /api/inspections/{id}

GET    /api/inspection-standards
POST   /api/inspection-standards
PUT    /api/inspection-standards/{id}
```

### 8.7 Files

```http
POST /api/files
GET  /api/files/{id}
```

### 8.8 Audit

```http
GET /api/logs?from=&to=&operator=&targetType=&storeId=
```

### 8.9 Assistant

```http
POST /api/assistant/query
POST /api/assistant/chat
```

`/api/assistant/query` 做规则查询，返回结构化结果。

`/api/assistant/chat` 可调用 AI，但必须基于规则查询结果和系统边界回答。

## 9. 数据助手设计

数据助手定位为“系统内经营数据助手”，不是通用聊天机器人。

允许回答：

- 某门店某月营业额、净利润、成本、费用。
- 某品牌某月排名。
- 亏损门店。
- 某门店多月趋势。
- 员工工资和人员名单。
- 报销状态统计。
- 巡店得分和扣分问题。
- 系统功能使用范围内的问题。

拒绝回答：

- 与本系统无关的问题。
- 涉及违法、攻击、破解、赌博、色情等屏蔽词的问题。
- 要求泄露 API Key、数据库密码、账号密码明文的问题。
- 无权限访问的门店数据。

回答流程：

1. 后端识别意图、月份、门店、品牌、指标。
2. 根据当前用户权限限制数据范围。
3. 查询 MySQL。
4. 生成结构化结果。
5. 如启用 DeepSeek，仅让 AI 基于结构化结果润色。
6. 返回答案和可追溯数据来源。

## 10. 数据迁移设计

迁移来源：

- `kv_storage.stores`
- `kv_storage.entries`
- `kv_storage.salary`
- `kv_storage.expenses`
- `kv_storage.inspections`
- `kv_storage.logs`
- `kv_storage.accounts`

迁移目标：

- `brand`
- `store_branch`
- `profit_entry`
- `salary_record`
- `expense_claim`
- `inspection_record`
- `operation_log`
- `auth_user`
- `user_store_scope`

迁移要求：

- 迁移前导出完整 JSON 备份。
- 迁移脚本幂等，可重复执行。
- 每类数据迁移完成后写入 `migration_run`。
- 迁移不删除 `kv_storage`。
- 对账失败时不能自动覆盖业务表。

对账指标：

- 门店总数。
- 品牌总数。
- 利润记录数。
- 已有月份列表。
- 工资记录数。
- 报销记录数。
- 巡店记录数。
- 指定月份净利润排名。
- 指定门店多月营业额。

## 11. 实施阶段

### 阶段 0：基线保护

目标：

- 提交当前性能优化。
- 将 `.superpowers/` 加入 `.gitignore`。
- 导出并保留当前数据。
- 确认旧系统仍可启动。

交付：

- 当前代码基线 commit。
- 数据备份文件。
- 浏览器打开验证记录。

验收：

- 旧系统仍可登录。
- 前端和后端端口能正常启动。
- 当前数据未丢失。

### 阶段 1：新系统骨架

目标：

- 建立前端新工程。
- 调整后端为领域模块化结构。
- 建立 API 客户端、权限上下文、统一布局。

交付：

- `web/` 或 `frontend/` 新 Vue 工程。
- 后端模块包结构。
- 登录页面和工作台壳。
- 基础路由和菜单。
- 公共组件第一版。

验收：

- 新前端能启动。
- 后端能启动。
- 登录页和工作台壳可访问。
- 不影响旧页面运行。

### 阶段 2：组织权限模块

目标：

- 登录后端化。
- 用户、角色、权限、门店范围后端化。
- 门店和品牌管理后端化。

交付：

- Auth API。
- Store API。
- Brand API。
- User API。
- 前端门店档案和用户权限页面。

验收：

- 错误密码无法登录。
- 店长只能看到绑定门店。
- 管理员可管理用户和门店。
- 越权接口调用被后端拒绝。

### 阶段 3：经营财务模块

目标：

- 利润录入、利润表、概览、排名、门店详情后端化。
- 后端统一计算口径。

交付：

- Profit Entry API。
- Report API。
- 新经营驾驶舱页面。
- 新利润录入页面。
- 新利润表和排名页面。
- `entries` JSON 迁移器。

验收：

- 迁移前后净利润排名一致。
- 新录入 2026-07 数据后刷新不丢。
- 报表结果由后端返回。
- 写操作进入日志。

### 阶段 4：运营和人事模块

目标：

- 员工工资、报销、巡店后端化。
- 图片改为文件上传。
- 审批和删除有日志。

交付：

- Salary API。
- Expense API。
- Inspection API。
- File API。
- 工资页面。
- 报销审批页面。
- 督导巡店页面。
- `salary`、`expenses`、`inspections` JSON 迁移器。

验收：

- 工资记录可新增、修改、删除、结转。
- 报销图片上传后刷新可回显。
- 报销审批状态正确。
- 巡店记录可查看详情和照片。
- 所有写操作有日志。

### 阶段 5：助手、导出、收尾上线

目标：

- 数据助手后端查询化。
- 操作日志页面后端化。
- 数据导出整合。
- 清理旧前端主路径。
- 完成部署文档。

交付：

- Assistant Query API。
- Logs API。
- Export API 第一版。
- 数据迁移对账报告。
- 部署 README。
- 旧存储依赖清理清单。

验收：

- 数据助手只回答系统内问题。
- 无权限数据不会被助手泄露。
- 操作日志可筛选。
- 禁用浏览器 localStorage 后新系统仍可运行。
- 新系统成为主入口。

## 12. 测试策略

后端测试：

- 利润计算单元测试。
- 权限判断单元测试。
- 数据范围测试。
- 迁移器幂等测试。
- 报销审批测试。
- 工资结转测试。
- 数据助手规则查询测试。

前端验证：

- 登录流程。
- 菜单权限。
- 门店筛选。
- 利润录入保存。
- 报表刷新。
- 工资新增和结转。
- 报销上传和审批。
- 巡店新增和详情。
- 数据助手问答。

数据对账：

- 迁移前后记录数一致。
- 迁移前后核心金额一致。
- 指定月份排名一致。
- 指定门店趋势一致。

浏览器验证：

- 桌面 1366 宽度。
- 桌面 1920 宽度。
- 移动端 390 宽度，只验证查看和轻量操作。

## 13. 风险和控制

### 风险：一次性改动过大

控制：

- 模块切片迁移。
- 每个阶段可运行。
- 每个模块独立验收。

### 风险：旧 JSON 字段不规范

控制：

- 迁移器做字段兼容。
- 迁移报告列出无法识别的数据。
- 不自动删除旧 `kv_storage`。

### 风险：权限前后端不一致

控制：

- 后端为准。
- 前端只隐藏按钮，不作为安全边界。
- 测试直接调用接口验证越权失败。

### 风险：文件上传和历史 base64 图片处理复杂

控制：

- 新文件走 `file_asset`。
- 旧 base64 在迁移时转为文件。
- 转换失败的图片保留原记录并在迁移报告标记。

### 风险：AI 助手回答越界

控制：

- 先规则查询。
- AI 只能基于查询结果回答。
- 后端屏蔽词和系统范围判断。
- 权限过滤先于 AI 调用。

## 14. 成功标准

全面重构完成时，系统必须满足：

- 新前端为主入口。
- 后端业务表为主数据源。
- 登录和权限由后端控制。
- 利润计算由后端统一返回。
- 门店、利润、工资、报销、巡店、日志都有业务 API。
- 写操作有操作日志。
- 数据助手基于后端数据回答。
- 旧 `kv_storage` 只作为历史备份，不作为主流程依赖。
- 迁移前后关键数据可对账。
- 本地启动和部署说明完整。

## 15. 下一步

用户确认本文档后，进入实施计划阶段。实施计划应按阶段 0 到阶段 5 拆成具体任务，并明确每个任务的文件范围、接口、测试和验收命令。

# 门店利润系统 Java 后端迁移设计

日期：2026-07-06

## 背景

当前系统是纯前端静态应用，核心文件是 `index.html` 和 `database.js`。页面、业务计算、权限判断、种子数据、数据读写都集中在前端。数据目前通过 `sGet` / `sSet` 访问，优先使用 CloudBase，其次尝试 `/api/storage`，最后落到浏览器 `localStorage`。

这种结构适合快速演示，但不适合正式多人使用。主要问题是：

- 数据在浏览器侧或 CloudBase KV 中，缺少清晰的数据模型和事务控制。
- 权限判断在前端，不能真正保护数据。
- 操作日志、报销、工资、巡店、利润录入缺少后端审计。
- 多人同时录入时容易出现覆盖。
- 业务计算散落在前端，后续维护和扩展困难。
- 数据助手只能读取前端内存数据，不能成为可靠的业务查询入口。

## 目标

将系统逐步改为前后端分离架构：

- 后端使用 Java Spring Boot。
- 数据库使用 MySQL。
- 前端界面暂时保留，逐步替换底层数据访问和业务逻辑。
- 第一阶段先让现有前端通过 Java 后端读写 MySQL。
- 后续阶段逐步把登录、权限、利润计算、报表、工资、报销、巡店、日志、数据助手迁移到后端服务。

## 非目标

第一阶段不重写前端 UI，不替换现有页面布局，不做复杂 AI 助手，不一次性拆完所有前端函数。

第一阶段不强制移除 CloudBase 和本地存储代码，但新的后端接口可用后，前端应优先走后端。

## 推荐架构

```text
store-profit-system-upload/
  backend/
    pom.xml
    src/main/java/...      Spring Boot 后端
    src/main/resources/
      application.yml
      db/migration/        Flyway MySQL 建表脚本
  frontend/
    index.html
    database.js
    cloudbase.full.js
    store-data-backup.json
  docs/
    superpowers/specs/
```

为了降低迁移风险，现有静态文件先移动到 `frontend/`。如果短期不移动，也可以先让后端读取当前根目录静态文件，但最终目录应分开。

后端分层：

- `controller`：REST API。
- `service`：业务逻辑、权限校验、利润计算、数据助手简单查询。
- `mapper`：MyBatis Plus 数据访问。
- `domain/entity`：数据库实体。
- `dto`：请求和响应对象。
- `security`：登录、JWT、角色权限。
- `common`：统一响应、异常处理、审计日志。

## 技术选型

- Java 21
- Spring Boot 3.x
- Maven
- MySQL 8.x
- MyBatis Plus
- Flyway
- JWT
- Lombok
- springdoc-openapi，用于生成接口文档

本机已检测到 Java 21 和 Maven 可用。MySQL 命令行当前未在 PATH 中，实施时需要确认 MySQL 服务、数据库名、账号和密码。

## 数据模型

第一阶段需要建这些核心表。

### auth_user

保存系统账号。

字段：

- `id`
- `username`
- `password_hash`
- `display_name`
- `role`
- `store_id`
- `enabled`
- `created_at`
- `updated_at`

角色包括：`ADMIN`、`BOSS`、`FINANCE`、`SUPERVISOR`、`STORE_MANAGER`。

### brand

保存品牌。

字段：

- `id`
- `name`
- `code`
- `color`
- `sort_order`

### store

保存门店。

字段：

- `id`
- `brand_id`
- `code`
- `name`
- `area`
- `manager`
- `open_date`
- `status`
- `note`
- `created_at`
- `updated_at`

### profit_entry

保存门店月度利润录入。

唯一约束：`store_id + month`

字段：

- `id`
- `store_id`
- `month`
- `sales`
- `refund`
- `discount`
- `material`
- `packaging`
- `loss`
- `cost_other`
- `rent`
- `labor`
- `utility`
- `property`
- `commission`
- `promo`
- `repair`
- `equip`
- `exp_other`
- `note`
- `created_by`
- `updated_by`
- `created_at`
- `updated_at`

净利润、净利率、成本合计、费用合计不落库为主，默认由后端计算返回，避免重复数据不一致。

### salary_record

保存员工工资。

字段保留现有前端工资模块需要的字段：

- `id`
- `store_id`
- `month`
- `employee_name`
- `position`
- `attendance`
- `gross`
- `normal_hours`
- `ot_hours`
- `work_hours`
- `vacation_left`
- `vacation_note`
- `base`
- `social`
- `post`
- `meal`
- `full_attendance`
- `commission`
- `overtime`
- `seniority`
- `late_night`
- `subsidy`
- `performance`
- `deduct_uniform`
- `return_uniform`
- `created_at`
- `updated_at`

### expense_claim

保存报销。

字段：

- `id`
- `store_id`
- `month`
- `amount`
- `category`
- `reason`
- `status`
- `image_url`
- `submitted_by`
- `reviewed_by`
- `reviewed_at`
- `created_at`
- `updated_at`

截图文件第一阶段可先存本地 `backend/uploads/`，数据库保存相对 URL。后续再替换为对象存储。

### inspection_record

保存督导巡店记录。

字段：

- `id`
- `store_id`
- `date`
- `inspector`
- `brand`
- `full_score`
- `score`
- `passed`
- `deductions_json`
- `redlines_json`
- `photos_json`
- `note`
- `created_at`
- `updated_at`

巡检扣分项第一阶段用 JSON 存储，避免一开始建太多从表。后续稳定后可拆成 `inspection_deduction` 和 `inspection_photo`。

### operation_log

保存操作日志。

字段：

- `id`
- `operator_id`
- `operator_name`
- `action`
- `target_type`
- `target_id`
- `store_id`
- `month`
- `before_json`
- `after_json`
- `reason`
- `created_at`

### kv_storage

第一阶段兼容现有前端的 `sGet` / `sSet`。

字段：

- `storage_key`
- `storage_value`
- `updated_at`

这个表只是过渡层，用于快速让现有前端接入 MySQL。第二阶段开始逐步减少依赖。

## API 设计

### 第一阶段兼容接口

`GET /api/storage?key=stores`

返回：

```json
{
  "value": "[JSON string]"
}
```

`POST /api/storage`

请求：

```json
{
  "key": "stores",
  "value": "[JSON string]"
}
```

兼容当前 `database.js` 中已有调用方式。前端只需要让 `/api/storage` 真正可用，就能把 `stores`、`entries`、`salary`、`expenses`、`logs`、`inspections`、`accounts`、`schema_v` 等现有数据写入 MySQL。

### 第二阶段业务接口

登录：

- `POST /api/auth/login`
- `GET /api/auth/me`

门店：

- `GET /api/stores`
- `POST /api/stores`
- `PUT /api/stores/{id}`
- `PATCH /api/stores/{id}/status`
- `DELETE /api/stores/{id}`

利润录入：

- `GET /api/profit-entries?month=2026-07`
- `GET /api/profit-entries/{storeId}/{month}`
- `PUT /api/profit-entries/{storeId}/{month}`
- `DELETE /api/profit-entries/{storeId}/{month}`

报表：

- `GET /api/reports/dashboard?month=2026-07`
- `GET /api/reports/ranking?month=2026-07&metric=net`
- `GET /api/reports/store/{storeId}`

工资：

- `GET /api/salary?month=2026-05&storeId=rg1`
- `POST /api/salary`
- `PUT /api/salary/{id}`
- `DELETE /api/salary/{id}`
- `POST /api/salary/carry-forward`

报销：

- `GET /api/expenses`
- `POST /api/expenses`
- `POST /api/expenses/{id}/approve`
- `DELETE /api/expenses/{id}`

巡店：

- `GET /api/inspections`
- `POST /api/inspections`
- `GET /api/inspections/{id}`
- `DELETE /api/inspections/{id}`

日志：

- `GET /api/logs`

数据助手：

- `POST /api/data-assistant/simple-query`

第一版数据助手只做规则查询：门店、月份、指标、排名、亏损、人员名单。不接 AI。

## 权限设计

权限必须在后端执行，前端隐藏按钮只作为体验优化。

规则：

- 管理员：全部权限。
- 老板：全门店只读。
- 财务：可录入、修改、导入、导出、报销处理，但不能删除核心数据。
- 督导：可创建和查看巡店记录。
- 店长：只可查看自己门店数据，可提交本店报销。

后端通过 JWT 识别当前用户。所有写接口必须记录操作日志。

## 利润计算设计

后端提供统一计算方法：

```text
income = sales - refund - discount
costSum = material + packaging + loss + cost_other
gross = income - costSum
expSum = rent + labor + utility + property + commission + promo + repair + equip + exp_other
net = gross - expSum
margin = income > 0 ? net / income : 0
grossMargin = income > 0 ? gross / income : 0
```

前端第一阶段可继续用原有 `calc`，第二阶段报表和数据助手改为使用后端计算结果。

## 迁移阶段

### 阶段 1：后端骨架和兼容存储

交付：

- `backend/` Spring Boot 项目。
- MySQL 连接配置。
- Flyway 建表脚本。
- `/api/storage` 兼容接口。
- CORS 配置。
- 统一响应和异常处理。
- README 中说明启动方式。

前端改动：

- `sGet` / `sSet` 优先访问后端 `/api/storage`。
- 保留 CloudBase 和 localStorage 作为降级路径。

验收：

- 后端能启动。
- 前端能通过后端保存和读取 `stores`、`entries` 等数据。
- 刷新页面后数据来自 MySQL。

### 阶段 2：账号登录和权限后端化

交付：

- 用户表。
- 登录接口。
- JWT。
- 后端权限拦截。
- 初始化管理员、老板、店长账号。

前端改动：

- 登录从本地 `findByPass` 改为 `/api/auth/login`。
- 角色和门店范围从 `/api/auth/me` 获取。

验收：

- 错误密码无法登录。
- 店长只能访问绑定门店数据。
- 只读角色调用写接口会被后端拒绝。

### 阶段 3：利润录入和报表接口

交付：

- 门店、品牌、利润录入业务表。
- 利润录入 CRUD。
- 仪表盘、排名、门店详情报表接口。
- 数据从 `kv_storage.entries` 迁移到 `profit_entry`。

前端改动：

- 数据录入页面调用 `profit-entries`。
- 利润概览、利润表、门店详情调用 `reports`。

验收：

- 录入 7 月数据后，报表立即读取后端计算结果。
- 排名、亏损筛查、净利率与前端旧算法一致。

### 阶段 4：工资、报销、巡店、日志业务化

交付：

- 工资接口。
- 报销接口和图片上传。
- 巡店接口。
- 操作日志接口。
- 对应 JSON 数据从 `kv_storage` 迁移到业务表。

前端改动：

- 员工工资、报销、巡店、日志模块逐个替换 API。

验收：

- 每个写操作有日志。
- 报销图片能上传并回显。
- 巡店记录刷新后仍从 MySQL 读取。

### 阶段 5：清理前端数据层

交付：

- 删除或弱化 `database.js` 中存储、权限、计算职责。
- 前端保留渲染、表单、导出交互。
- 后端成为唯一可信数据源。

验收：

- 禁用 localStorage 后系统仍能正常使用。
- 前端无法绕过权限直接改数据。

## 数据迁移策略

第一阶段先使用 `kv_storage` 保存现有 JSON，避免丢数据。

第二阶段提供迁移服务：

1. 读取 `kv_storage.stores` 写入 `brand` 和 `store`。
2. 读取 `kv_storage.entries` 写入 `profit_entry`。
3. 读取 `kv_storage.salary` 写入 `salary_record`。
4. 读取 `kv_storage.expenses` 写入 `expense_claim`。
5. 读取 `kv_storage.inspections` 写入 `inspection_record`。
6. 读取 `kv_storage.logs` 写入 `operation_log`。
7. 迁移完成后保留 `kv_storage` 作为备份，不立即删除。

迁移脚本必须幂等：重复执行不会重复插入同一条业务数据。

## 前端替换策略

保持页面可用优先。

第一步只改数据入口：

```js
sGet("entries") -> GET /api/storage?key=entries
sSet("entries", data) -> POST /api/storage
```

第二步按模块替换：

```text
登录 -> 门店/品牌 -> 利润录入 -> 报表 -> 工资 -> 报销 -> 巡店 -> 日志 -> 数据助手
```

每替换一个模块，都保留旧逻辑一段时间作为对照，确认结果一致后再清理。

## 错误处理

后端统一返回：

```json
{
  "success": false,
  "message": "错误说明",
  "code": "ERROR_CODE"
}
```

前端收到错误后显示现有 `toast`，并在控制台输出详细错误。

常见错误：

- 未登录。
- 无权限。
- 数据不存在。
- 月份格式错误。
- 门店不可访问。
- 数据库保存失败。
- 文件上传失败。

## 测试计划

后端测试：

- 利润计算单元测试。
- 权限判断单元测试。
- `/api/storage` 读写集成测试。
- 登录接口测试。
- 利润录入和报表接口测试。

前端验证：

- 登录后页面能加载。
- 门店数据能从后端读取。
- 保存利润录入后刷新仍存在。
- 7 月数据助手简单问答能返回“暂无数据”或正确结果。
- 店长角色看不到其他门店。

数据校验：

- 使用现有 `store-data-backup.json` 导入后，核心门店数和利润记录数一致。
- 迁移前后同一月份净利润排名一致。

## 风险和应对

风险：现有数据结构是大 JSON，字段不完全规范。

应对：第一阶段保留 `kv_storage`，第二阶段再逐步迁移业务表。

风险：前端代码集中在一个大文件，直接大改容易引入页面问题。

应对：只替换数据入口和模块接口，不先重写 UI。

风险：MySQL 环境未就绪。

应对：后端配置使用环境变量；README 写清数据库创建命令和配置项。开发时必须连接 MySQL，不用 H2 作为主方案。

风险：权限前端和后端短期并存，可能出现表现不一致。

应对：以后端权限为准，前端隐藏只做体验。

## 验收标准

阶段 1 完成时：

- `backend` 可以通过 Maven 启动。
- MySQL 表能通过 Flyway 自动创建。
- 前端能通过 Java 后端读写现有 JSON 数据。
- 页面刷新后数据仍存在。
- README 包含启动后端、配置 MySQL、打开前端的步骤。

完整迁移完成时：

- 核心数据存储在 MySQL 业务表。
- 登录和权限由后端控制。
- 利润计算和报表由后端返回。
- 工资、报销、巡店、日志都有后端接口。
- 前端不再依赖 localStorage 作为主数据源。
- 数据助手简单查询走后端接口。

## 需要实施前确认的配置

实施阶段需要确定：

- MySQL 主机：默认 `localhost`
- MySQL 端口：默认 `3306`
- 数据库名：默认 `store_profit`
- 用户名：默认 `root`
- 密码：由本机环境决定，不能写死在代码中

这些配置会放在 `application.yml`，并允许通过环境变量覆盖。

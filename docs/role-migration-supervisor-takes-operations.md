# 督导接管运营角色迁移设计

## 状态与目标

状态：R1-1 代码级实施与全量回归已完成（2026-07-20）；QA Flyway、服务启动、登录验证和 45 项业务测试尚未执行。

正式角色收敛为：`BOSS`、`FINANCE`、`WAREHOUSE`、`STORE_MANAGER`、`SUPERVISOR`、`EMPLOYEE`。

`ADMIN`、`OWNER` 只作为历史输入归一为 `BOSS`；`OPS`、`OPERATIONS` 只作为历史登录、导入和迁移输入归一为 `SUPERVISOR`。它们都不能再被创建、显示为业务角色、获取独立菜单或作为独立 API 授权角色。

`SUPERVISOR` 将同时承担巡检、问题、整改、日报损复核/导出，以及原运营工作台、平台配置、企迈/配方、培训/考试、盘存和运营数据辅助能力。该合并不授予财务写入、工资管理、账号权限或总仓采购/入库/发货能力。

## A1 审计结果

审计命令使用 `rg` 在 `backend`、`frontend-vue`、数据库迁移、测试、脚本、文档、移动端和 GitHub 工作流中查找：`BOSS`、`FINANCE`、`WAREHOUSE`、`STORE_MANAGER`、`SUPERVISOR`、`OPERATIONS`、`OPS`、`ADMIN`、`OWNER`。审计忽略依赖、构建产物和二进制文件；历史文档和测试资料保留为可追溯输入，不作为前台角色来源。

### 登录与角色标准化

- 后端标准化入口为 `platform/auth/AccessControlService.canonicalRole`；认证、会话、默认工作台和权限均依赖该结果。
- 前端标准化入口为 `frontend-vue/src/permissions/roles.ts`；当前将 `OPS` 归一为 `OPERATIONS`，并把运营显示为正式角色，需改为归一为 `SUPERVISOR` 且不展示旧角色。
- `AuthService`、`AuthRepository`、`UserManagementService`、登录/会话测试以及演示账号脚本都存在旧角色输入或角色显示引用。
- 当前用户管理服务的 `ALLOWED_ROLES` 仍包含 `OPERATIONS`，必须收敛为六个正式角色，并以中文业务错误拒绝 `OPERATIONS`/`OPS` 的新建和编辑请求。

### 权限模板与个人权限覆盖

- `AuthorizationService` 的 `SUPERVISOR_PERMISSION_DENYLIST` 同时移除了运营工作台、平台、盘存、考试管理和考试报表权限；这正是督导无法接管运营的核心阻断。
- 同样的督导黑名单和拒绝文案存在于 `UserManagementService`，会阻止对督导的个人授权覆盖。
- `AuthorizationService.legacyTemplatePermissions` 分别维护 `OPERATIONS` 与 `SUPERVISOR` 模板；实施时将以两者并集为督导模板，再应用财务、工资、账号、门店管理和总仓管理的硬边界。
- 角色权限数据来自 `role_permission`，个人覆盖来自 `user_permission_override`。迁移必须保留后者，并让已允许的运营权限在迁移后的督导账号上继续生效；显式 DENY 仍优先。

### 菜单、路由守卫与默认工作台

- 前端的 `permissions/menu.ts`、`permissions/workspaces.ts`、`stores/auth.ts` 仍保留运营工作台、`OPERATIONS` allowedRoles 和 `/operations` 默认工作台。
- 后端 `WorkspaceAccessResolver` 同样将 `OPERATIONS` 映射为 `/operations`，而 `SUPERVISOR` 当前只进入 `/operations/inspection`。
- 路由、`OperationsWorkspace.vue`、培训考试、平台配置、盘存、督导巡检与整改队列均包含 `OPERATIONS` 专属条件；应复用现有 `/operations` 与 `/operations/inspection` 路由，但普通用户文案改为“督导工作台”。
- `/operations` 作为历史路径可继续保留，以避免深链接失效；其访问条件、菜单和默认路由都应改由 `SUPERVISOR` 驱动。

### 数据范围与平台/品牌范围

- `DataScopeService` 为 `SUPERVISOR` 和 `OPERATIONS` 分别生成范围，后者覆盖 `STORE`、`WAREHOUSE`、`INSPECTION`、`EXAM`、`PLATFORM` 等域；督导目前只允许较窄域。
- `UserManagementService.validateRoleScope` 也将督导限制在旧的督导域。实施时必须扩展督导的受控域至原运营所需的 `PLATFORM`、`EXAM`、运营辅助数据域，同时保留 `STORE_LIST`/`NONE` 的最小权限模式。
- 不能将督导范围升级为 `ALL`；平台、品牌、门店、附件、员工和仓库访问仍由既有数据范围解析器和 Controller/Service 强制校验，越权保持 403。

### 用户管理与审计日志

- 用户管理 API、用户权限页、权限目录和角色说明需要只显示六个正式角色，并明确督导职责。
- 所有旧角色输入拒绝、角色迁移和权限版本更新必须写操作日志；日志只记录角色代码、用户 ID、租户、动作和结果，不记录密码、令牌、API Key、附件内容或客户数据。
- `operation_log` 中的历史操作者/角色快照不得更新或重写。账号角色变更只能影响 `auth_user`、权限模板和未来会话。

### 数据库迁移

- 现有角色与权限基础迁移包括 `V31__unify_boss_role.sql`、`V37__permission_architecture.sql`、`V39__restore_permission_catalog_and_role_templates.sql`、`V45`、`V46`、`V47`、`V50`、`V62`、`V67`，MySQL 与 H2 目录均有对应文件。
- 新迁移不得改写上述已执行文件。它将：将 `auth_user.role='OPERATIONS'` 归并为 `SUPERVISOR`；将运营模板权限以去重方式并入督导模板；禁用或删除运营模板关联；保留 `auth_user` 主键、租户、门店/品牌/仓库范围、个人覆盖、业务记录和审计归属；使迁移可重复执行。
- 需要同时提供 H2 迁移，以保持后端集成测试的 Flyway 路径可用。

### 测试、QA 脚本与文档

- 后端测试集中在 `platform/auth`、`platform/authorization`、`platform/users`、`operations`、`inspection`、`dailyloss`、`warehouse` 和迁移测试；它们包含旧运营正反向断言，需要改为“旧输入兼容、正式授权拒绝”。
- 前端 E2E 包括角色路由、菜单、待办、权限架构、巡检、平台、培训、员工助手和响应式用例；需覆盖六个正式角色、旧账号迁移与督导运营能力。
- QA 初始化脚本、演示账号模板、验收矩阵、角色矩阵、功能清单及发布文档均有 `OPERATIONS` 引用。当前内部试用计划与本设计的角色目标一致，但验收矩阵仍需改写角色归属。

## 数据迁移策略

1. 先确保 `SUPERVISOR` 模板拥有“原督导模板 ∪ 原运营模板”的允许权限，使用唯一约束或 `NOT EXISTS` 去重；不复制任何硬禁止权限。
2. 保持个人覆盖原样。迁移后由新的督导硬边界过滤掉财务写入、工资、账号权限、门店管理和总仓管理，即使旧数据存在个人 ALLOW。
3. 将 `auth_user` 的 `OPERATIONS` 角色更新为 `SUPERVISOR`，保留用户 ID、tenant ID、绑定门店、品牌、仓库范围和权限版本；递增权限版本并删除该用户旧 token，强制下次会话以督导身份重新加载权限。
4. 对历史兼容输入，`ADMIN`/`OWNER` 归一为 `BOSS`，`OPS`/`OPERATIONS` 归一为 `SUPERVISOR`；新建和编辑 API 不接受这些输入。
5. 不更新 `operation_log` 的历史字段，也不写入密码或密钥。迁移审计只记录一次性迁移动作与影响账号数量。

## 回滚边界

- Flyway 为前向迁移：不提供将已迁移用户重新降回 `OPERATIONS` 的数据库回滚脚本，因为旧角色已被明确废止。
- 应用回退版本仍必须能识别已变为 `SUPERVISOR` 的账号；若不能兼容，只能先恢复已验证的 QA 数据库备份，不能手工修改生产角色。
- 权限模板和用户角色的恢复通过新的、可审计的前向补偿迁移或 QA 基线恢复完成；`operation_log` 永不回滚或篡改。

## 测试策略

1. 单元与集成测试：角色标准化、模板合并、个人 ALLOW/DENY、中文拒绝、默认工作台、数据范围、旧账号迁移、401/403 与审计。
2. Flyway：空 QA/H2 库与升级 QA 库都执行到候选版本；断言运营账号改为督导、范围/覆盖保留、模板去重、历史日志未变。
3. 前端：角色选择器仅有六项；督导菜单含巡检、整改复核、日报损复核/导出、平台、培训考试、盘存；旧运营不显示；直达 URL 与 API 都遵守权限。
4. 回归：执行后端测试和打包、Vue 类型检查/构建、角色与路由 Playwright。内部试用 G0-G7 只有在可恢复 QA MySQL、受控账号和外部能力测试配置全部就绪后才能开始。

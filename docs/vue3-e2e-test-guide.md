# Vue3 E2E 回归测试指南

本文档说明 Vue3 发布前固定回归测试流程。默认测试只读，不执行仓库叫货、确认收货、报销审核、老板关闭事项等写入动作。

## 测试目录

```text
frontend-vue/
├─ playwright.config.ts
├─ tests/e2e/
│  ├─ auth.setup.ts
│  ├─ 00-health.spec.ts
│  ├─ 01-role-routing.spec.ts
│  ├─ 02-menu-permission.spec.ts
│  ├─ 03-todos-boundary.spec.ts
│  ├─ 04-core-pages.spec.ts
│  ├─ 05-business-readonly.spec.ts
│  ├─ 06-responsive.spec.ts
│  └─ 07-storage-policy.spec.ts
└─ test-results/
```

## 启动后端

```powershell
cd backend
$env:MYSQL_PASSWORD = 'a'
java -jar target/store-profit-backend-0.1.0-SNAPSHOT.jar
```

健康检查地址：

```text
http://127.0.0.1:8080/api/health
```

## 启动 Vue3

```powershell
cd frontend-vue
npm install
npm run dev
```

默认地址：

```text
http://127.0.0.1:5173
```

## 发布前验证命令

```powershell
cd frontend-vue
npm run build
npm run test:e2e
```

如只跑 P0 冒烟：

```powershell
npm run test:e2e:smoke
```

查看 HTML 报告：

```powershell
npm run test:e2e:report
```

报告目录：

```text
frontend-vue/test-results/html-report
```

## 发布前完整门禁脚本

本地发布前或云服务器部署前，优先运行完整检查脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-vue3-release.ps1
```

脚本执行内容：

- 检查后端 `/api/health`
- 检查 Vue3 `/login`
- 执行 `npm run build`
- 执行 `npm run test:e2e`
- 归档 Playwright 报告和日志

报告归档位置：

```text
output/vue3-release-check/yyyyMMdd-HHmmss/
```

脚本不会自动启动或停止后端、Vue3，也不会执行写入类 E2E。运行前需要确认：

- 后端已经在 `http://127.0.0.1:8080` 可访问
- Vue3 已经在 `http://127.0.0.1:5173` 可访问

如地址不同，可指定参数：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-vue3-release.ps1 `
  -FrontendUrl "http://127.0.0.1:5173" `
  -ApiBaseUrl "http://127.0.0.1:8080"
```

建议运行完整门禁的场景：

- 本地准备发布 Vue3 前端前
- 云服务器部署 Vue3 前
- 修改角色路由、菜单权限、今日待办、localStorage 策略后
- 旧 HTML 下线前

## 发布后 Smoke 检查脚本

发布后快速确认页面和核心权限可用：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-vue3-smoke.ps1
```

脚本执行内容：

- 检查后端 `/api/health`
- 检查 Vue3 `/login`
- 执行 `npm run test:e2e:smoke`
- 归档 smoke 报告和日志

报告归档位置：

```text
output/vue3-smoke-check/yyyyMMdd-HHmmss/
```

建议只跑 smoke 的场景：

- 云服务器前端文件上传后
- Nginx 配置变更后
- 快速确认登录、默认首页、菜单权限、关键 403 是否正常

## 环境变量

测试默认地址：

- Vue3：`http://127.0.0.1:5173`
- 后端：`http://127.0.0.1:8080`

可覆盖：

```powershell
$env:E2E_BASE_URL = 'http://127.0.0.1:5173'
$env:E2E_API_URL = 'http://127.0.0.1:8080'
```

角色账号也可以覆盖：

```powershell
$env:E2E_BOSS_USERNAME = '<老板测试账号>'
$env:E2E_BOSS_PASSWORD = '<受控测试密码>'
$env:E2E_FINANCE_USERNAME = '<财务测试账号>'
$env:E2E_FINANCE_PASSWORD = '<受控测试密码>'
$env:E2E_WAREHOUSE_USERNAME = '<仓库测试账号>'
$env:E2E_WAREHOUSE_PASSWORD = '<受控测试密码>'
$env:E2E_SUPERVISOR_USERNAME = '<督导测试账号>'
$env:E2E_SUPERVISOR_PASSWORD = '<受控测试密码>'
$env:E2E_OPERATIONS_USERNAME = '<运营测试账号>'
$env:E2E_OPERATIONS_PASSWORD = '<受控测试密码>'
$env:E2E_STORE_USERNAME = '<店长测试账号>'
$env:E2E_STORE_PASSWORD = '<受控测试密码>'
```

不要把 MySQL 密码、密钥或真实 token 写进测试代码。

## 当前覆盖范围

P0 发布门槛：

- 后端 `/api/health`
- Vue `/login`
- 六个角色登录
- 默认首页跳转
- 无权限页和关键 403 接口
- `npm run build`

P1 角色回归：

- 老板：`/boss`
- 财务：`/finance`
- 仓库管理员：`/warehouse`
- 店长：`/warehouse`
- 督导：`/inspection`
- 运营：`/operations`
- 菜单权限正确
- 今日待办只提醒和跳转，不承载复杂业务操作

P2 只读业务检查：

- 核心页面能加载
- 页面不展示英文技术状态码
- 运行态 localStorage 只保存登录态
- 源码不把真实业务数据写入 localStorage、sessionStorage 或 indexedDB
- 移动端宽度 `375/390/430/768` 不出现整页横向溢出

## 写入类测试规则

默认不跑写入类测试，避免污染 MySQL。

后续如新增写入流程测试，必须放在：

```text
frontend-vue/tests/e2e/write/
```

并且只能在显式开启时运行：

```powershell
$env:E2E_WRITE = '1'
npm run test:e2e:write
```

所有写入数据必须使用测试前缀：

```text
E2E-20260709-仓库叫货
E2E-20260709-财务审核
```

测试结束要清理数据，或在报告中列出产生的数据。

写入类测试默认关闭的原因：

- 仓库叫货、确认收货、报销审核、老板关闭事项都会写 MySQL。
- 默认回归测试应保持只读，避免污染真实业务数据。
- 写入测试必须使用 `E2E-YYYYMMDD-*` 前缀，并具备清理或记录机制。

## GitHub Actions

已提供：

```text
.github/workflows/vue3-ci.yml
```

默认执行：

- checkout
- setup Node.js
- `cd frontend-vue`
- `npm ci`
- `npm run build`

`npm run test:e2e:smoke` 默认不在 push / pull_request 中运行，因为 CI 当前没有稳定的后端和 MySQL 服务。后续如果提供可访问的 `E2E_BASE_URL` 和 `E2E_API_URL`，可以通过手动 workflow 参数启用 smoke。

## 新增角色测试方法

1. 在 `frontend-vue/tests/e2e/auth.setup.ts` 增加角色配置。
2. 补充默认首页、期望菜单、禁止菜单。
3. 如有专属页面，在 `04-core-pages.spec.ts` 增加页面加载检查。
4. 如有权限边界，在 `02-menu-permission.spec.ts` 增加无权限页或 403 检查。
5. 如涉及今日待办跳转，更新 `03-todos-boundary.spec.ts`，但不要在今日待办中做复杂业务操作。

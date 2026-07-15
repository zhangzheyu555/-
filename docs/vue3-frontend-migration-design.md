# Vue3 前端迁移设计 v1（历史归档）

> 状态：已归档（2026-07-15）。本文记录早期从旧静态页迁移到 Vue3 的决策，当中的“旧系统仍是主线”“8080/index.html 可作为业务入口”等描述已经失效，不能用于开发或发布。当前唯一正式前端是 `frontend-vue`；部署请使用 [Vue3 生产部署说明](./vue3-production-deployment.md) 和 [Vue3 Nginx 配置](./nginx-vue3-ai-profit-os.conf)。

项目：AI Profit OS / 多门店经营异常处理系统

## 迁移目标

当前主线前端仍是 `runtime-static/index.html` 和 `backend/src/main/resources/static/index.html`。它们承载了登录、今日待办、仓库中心、数据助手、报销、门店详情等大量功能，继续扩展会增加维护风险。

Vue3 重构目标不是一次性推翻旧系统，而是在 `frontend-vue/` 中建立新前端骨架，先保证登录、角色导航、接口调用和仓库只读视图可用，再逐步迁移业务模块。

## 新旧前端并存策略

- 旧系统继续通过 `http://127.0.0.1:8080/index.html` 使用。
- 新 Vue 前端在本地通过 `http://127.0.0.1:5173` 使用。
- 第一阶段不替换 Spring Boot jar 内置页面。
- 第一阶段不删除、不覆盖 `runtime-static/index.html`、`backend/src/main/resources/static/index.html`、顶层 `index.html`。
- Vue 前端通过 Vite dev server 代理 `/api` 到 `http://127.0.0.1:8080`。

## 第一阶段范围

已建立：

- `Vue 3`
- `Vite`
- `TypeScript`
- `Pinia`
- `Vue Router`
- `Axios` API 封装

第一阶段页面：

- 登录页：调用 `POST /api/auth/login`。
- 主布局：左侧导航、顶部角色和后端健康状态。
- 今日待办：占位页。
- 仓库中心：调用 `GET /api/warehouse/overview`，按角色显示只读视图。
- 数据助手：角色化占位页。

## 第二阶段范围

第二阶段迁移店长端仓库中心，不迁移仓库管理员完整后台。

已迁移到 `frontend-vue`：

- 顶部统计卡片：本店商品、我的叫货单、待仓库处理、待确认收货。
- 商品分类：只读筛选，支持后端商品类别和默认类别兜底。
- 本店库存：显示商品、分类、本店库存、公司仓库可配送、库存状态。
- 向公司仓库叫货：选择商品、填写数量和备注，调用 `POST /api/warehouse/requisitions`。
- 我的叫货单：显示单号、商品、状态、提交时间。
- 待确认收货：只显示 `SHIPPED` 状态叫货单，调用 `POST /api/warehouse/requisitions/{id}/receive`。

仍未迁移：

- 仓库管理员商品档案维护。
- 采购到货入库。
- 预警设置。
- 出库记录和打印单下载。
- 配送退货单完整 Vue 流程。

仓库管理员进入 Vue 仓库中心时，第二阶段保留只读库存和待处理叫货展示，并提示后台将在后续迁移。

## 第三阶段范围

第三阶段迁移 `今日待办` 提醒页，核心原则是：

```text
今日待办只提醒、只汇总、只跳转，不承载复杂业务操作。
```

已迁移到 `frontend-vue`：

- 待办 API 封装：`frontend-vue/src/api/todos.ts`。
- 待办 Pinia 状态：`frontend-vue/src/stores/todos.ts`。
- 统计卡片、提醒卡片、提醒分区组件。
- `TodayTodoPage.vue` 从占位页升级为角色化提醒页。

角色化提醒：

- 老板：需要我处理、高风险提醒、各岗位处理中、已处理复盘。
- 店长：待确认收货、本店经营提醒、巡店整改、报销补资料。
- 仓库管理员：门店叫货待处理、库存不足、退货待入库、已处理记录。
- 财务：报销待审核、利润异常、工资待核对、已上报老板。
- 督导：巡店整改、高风险、待复查、已处理。
- 运营：数据提醒、高风险、待处理、已处理。

第三阶段不迁移：

- 仓库发货、采购入库、预警设置、退货入库等复杂业务操作。
- 财务审核、报销补充、巡店整改提交等复杂表单。
- 老板处理完成、无影响关闭等写入动作。

这些操作必须回到对应业务页面完成。今日待办只提供清晰提醒和跳转入口。

## 数据规则

真实业务数据不得存入浏览器本地存储。

禁止存储：

- 库存
- 叫货单
- 退货单
- 商品档案
- 今日待办
- 报销记录
- 财务数据

允许存储：

- 登录 token
- 当前登录用户基础信息
- 前端 UI 状态
- 临时筛选条件

所有真实业务数据必须来自后端 API，并由后端写入 MySQL。前端权限只负责显示控制，后端接口权限仍是最终边界。

## 角色权限边界

老板：

- 可查看全局经营、风险、仓库单据。

店长：

- 只看本门店。
- 不显示商品编辑、采购入库、预警设置。
- 不显示采购成本。

仓库管理员：

- 管理仓库全流程。
- 不进入利润、工资、老板全局经营数据。

财务：

- 查看利润、费用、报销、单据相关数据。

督导：

- 查看巡店、整改、门店检查数据。

运营：

- 查看运营配置、数据导入和同步状态。

## 后续迁移顺序

建议顺序：

1. 数据助手：按老板、店长、仓库、财务、督导、运营拆分上下文。
2. 商品档案和仓库管理员工作台。
3. 配送退货单 Vue 流程。
4. 老板、财务、督导、运营页面。
5. 手机端专项优化。

## 本地开发命令

后端：

```powershell
cd backend
$env:MYSQL_PASSWORD='<本机MySQL密码>'
java -jar target/store-profit-backend-0.1.0-SNAPSHOT.jar
```

Vue 前端：

```powershell
cd frontend-vue
npm install
npm run dev
```

访问：

- 旧系统：`http://127.0.0.1:8080/index.html`
- Vue 新前端：`http://127.0.0.1:5173`

构建：

```powershell
cd frontend-vue
npm run build
```

## 部署建议

第一阶段不切换生产入口。后续切换时建议：

- Vue 构建产物：`frontend-vue/dist/`
- Nginx `/` 指向 Vue dist
- Nginx `/api/**` 代理到 Spring Boot 8080
- Spring Boot 保留内置静态页作为兜底

推荐云服务器目录：

```text
/opt/store-profit/frontend/
/opt/store-profit/backend/
```

## 验收标准

第一阶段完成后至少验证：

- 旧系统 `http://127.0.0.1:8080/index.html` 仍可访问。
- `npm install` 成功。
- `npm run build` 成功。
- `npm run dev` 成功。
- 使用受控验收账号验证老板、店长和仓库管理员登录。
- 测试账号密码仅由测试环境密钥管理提供，不记录在仓库文档中。
- 登录后显示对应角色导航。
- 仓库中心可读取 `GET /api/warehouse/overview`。
- 店长只看到本店库存和叫货单只读视图。
- 仓库管理员看到仓库库存和待处理叫货只读视图。

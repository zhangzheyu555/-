# AI Profit OS Vue3 生产上线验收清单

## 上线目标

Vue3 作为默认入口上线，旧 HTML 只作为 `/legacy/index.html` 兜底入口。Spring Boot 继续只负责 `/api/**`，所有真实业务数据必须写入 MySQL。

## 入口检查

- [ ] `/` 打开 Vue3。
- [ ] `/login` 打开 Vue3 登录页。
- [ ] `/todos` 打开 Vue3 今日待办。
- [ ] `/boss` 打开老板驾驶舱。
- [ ] `/finance` 打开财务工作台。
- [ ] `/warehouse` 打开仓库工作台或店长仓库中心。
- [ ] `/inspection` 打开督导工作台。
- [ ] `/operations` 打开运营中心。
- [ ] `/legacy/index.html` 打开旧 HTML 兜底。
- [ ] `/api/health` 返回后端健康状态。
- [ ] 刷新 `/boss`、`/finance`、`/warehouse`、`/inspection`、`/operations`、`/todos` 不 404。

## 构建与打包

- [ ] `cd frontend-vue && npm run build` 通过。
- [ ] `frontend-vue/dist/index.html` 存在。
- [ ] 构建产物不依赖本地 `5173` 开发服务。
- [ ] 构建产物未发现明文密码、数据库密码、API Key、DeepSeek Key、测试 token。
- [ ] `cd backend && mvn -q test` 通过。
- [ ] `cd backend && mvn -q -DskipTests package` 通过。
- [ ] jar 可启动。
- [ ] `/api/health` 正常。

## 角色默认首页

- [ ] 使用受控验收账号登录后，老板进入 `/boss`。
- [ ] 使用受控验收账号登录后，财务进入 `/finance`。
- [ ] 使用受控验收账号登录后，仓库管理员进入 `/warehouse`。
- [ ] 使用受控验收账号登录后，督导进入 `/inspection`。
- [ ] 使用受控验收账号登录后，运营进入 `/operations`。
- [ ] 使用受控验收账号登录后，店长进入店长仓库中心或本店首页。

## 权限边界

- [ ] 老板可以看老板驾驶舱和处理上报老板事项。
- [ ] 老板驾驶舱不直接执行仓库发货、财务审核、店长确认收货。
- [ ] 财务可以进财务工作台，不能处理老板事项，不能仓库发货。
- [ ] 仓库管理员可以进仓库工作台，不能进财务审核，不能处理老板事项，不能店长确认收货。
- [ ] 督导可以进督导工作台，不能进财务审核、仓库发货、老板关闭事项。
- [ ] 运营可以进运营中心，不能处理财务、仓库、老板专属动作。
- [ ] 店长只能看本门店数据，只能本店叫货、确认本店收货、处理本店整改。
- [ ] 店长不能进入仓库管理员工作台。

## 接口 403 验收

必须直接调用接口验证，不能只看菜单隐藏。

- [ ] finance 调 `/api/boss/todo-dashboard` 返回 403。
- [ ] warehouse 调 `/api/boss/todo-dashboard` 返回 403。
- [ ] rg1 调 `/api/finance/workbench` 返回 403。
- [ ] rg1 调仓库管理员发货接口返回 403。
- [ ] finance 调仓库发货接口返回 403。
- [ ] warehouse 调财务审核接口返回 403。
- [ ] ops 调老板处理接口返回 403。

## MySQL 数据落库

以下真实业务动作必须写 MySQL，不允许长期存浏览器本地：

- [ ] 今日待办处理动作写 `todo_action`。
- [ ] 附件写 `todo_action_attachment` 或附件相关表。
- [ ] 上报老板写 `todo_escalation`。
- [ ] 财务报销审核、驳回、补资料写财务和日志表。
- [ ] 老板处理完成、无影响关闭写待办动作和日志。
- [ ] 仓库叫货写 `store_requisition`、`store_requisition_line`。
- [ ] 仓库发货写 `warehouse_stock_movement`。
- [ ] 店长确认收货写 `store_inventory`、`store_inventory_movement`。
- [ ] 采购入库写 `warehouse_stock_batch`、`warehouse_stock_movement`。
- [ ] 库存预警配置写仓库商品或预警字段。
- [ ] 配送退货写 `warehouse_return_order`、`warehouse_return_order_line`。
- [ ] 巡店记录和整改复查写巡店相关表。
- [ ] 运营待办处理写 `todo_action` 和 `operation_log`。

## 浏览器存储扫描

允许：

- token。
- 当前用户基础信息。
- UI 临时状态。
- 非业务关键偏好。

禁止：

- todo、expense、warehouse、inventory、requisition、return、inspection、salary、profit、boss action、operation log 等真实业务数据。

验收动作：

- [ ] 扫描 `frontend-vue/src` 中 `localStorage`、`sessionStorage`、`indexedDB`。
- [ ] 扫描 `frontend-vue/dist` 中敏感词。
- [ ] 发现真实业务数据写浏览器时，必须修复后再上线。

## 附件与 PDF

- [ ] 上传图片和附件走后端接口。
- [ ] 附件记录写 MySQL。
- [ ] 附件大小和类型有限制。
- [ ] 无权限不能下载别人的附件。
- [ ] 入库单 PDF 从 MySQL 生成。
- [ ] 出库单 PDF 从 MySQL 生成。
- [ ] 配送退货单 PDF 从 MySQL 生成。
- [ ] 下载 PDF 写 `operation_log`。
- [ ] 店长只能下载自己门店相关单据。
- [ ] 仓库管理员和老板按权限下载全部仓库单据。

## Nginx

- [ ] `docs/nginx-vue3-ai-profit-os.conf` 已配置。
- [ ] `/api/**` 反向代理到 `http://127.0.0.1:8080/api/`。
- [ ] `/legacy/` 指向 `/opt/store-profit/legacy-frontend/`。
- [ ] `/` 使用 `try_files $uri $uri/ /index.html`。
- [ ] `index.html` 不强缓存。
- [ ] 静态资源 no-cache 或短缓存。
- [ ] `nginx -t` 通过。
- [ ] `systemctl reload nginx` 后访问正常。

## 部署与回滚

- [ ] `scripts/deploy-vue3-frontend.ps1` 可构建并上传 Vue3 dist。
- [ ] 上传前备份旧前端到 `frontend-backup/yyyyMMdd-HHmmss/`。
- [ ] 前端发布不重启 Java。
- [ ] `scripts/rollback-vue3-frontend.ps1` 可列出备份。
- [ ] 可恢复最近一次或指定前端备份。
- [ ] 后端 jar 上传前备份到 `backend-backup/`。
- [ ] 后端回滚后重启 systemd 并检查 `/api/health`。
- [ ] MySQL 有上线前备份。

## 移动端检查

- [ ] 375px 宽度无整页横向溢出。
- [ ] 390px 宽度无整页横向溢出。
- [ ] 430px 宽度无整页横向溢出。
- [ ] 768px 宽度布局正常。
- [ ] 顶部菜单按钮可以打开和关闭抽屉。
- [ ] 表格可以横向滚动。
- [ ] 按钮高度适合触控。

## 安全检查

- [ ] 仓库和构建产物未发现密钥。
- [ ] 不打印密码。
- [ ] 不把 token 写日志。
- [ ] 文档不包含数据库密码、API Key、DeepSeek Key。
- [ ] 生产敏感值通过环境变量或服务器配置提供。
- [ ] CORS 不对不可信域名无限放开。
- [ ] 文件上传限制类型和大小。
- [ ] PDF 和附件下载权限正确。

## 性能观察

- [ ] Vue3 首页首屏无长时间白屏。
- [ ] `/api/health` 快速返回。
- [ ] 老板驾驶舱接口响应可接受。
- [ ] 今日待办接口响应可接受。
- [ ] 仓库 overview 接口响应可接受。

发现慢接口时，本阶段先记录问题；除非是明显缺索引或错误查询，不在上线切换阶段大改业务 SQL。

## 已知风险记录

上线前需要填写：

- [ ] 未验证账号：
- [ ] 未验证接口：
- [ ] 未验证写库动作：
- [ ] 未完成权限兜底：
- [ ] 当前必须保留的回滚入口：
- [ ] 上线窗口负责人：
- [ ] 回滚触发条件：

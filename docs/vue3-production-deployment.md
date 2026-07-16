# AI Profit OS Vue3 生产部署说明

## 目标

Vue3 新前端作为正式入口，旧 HTML 只保留为只读回看和视觉参考入口。

部署边界：

- Vue3 前端：Nginx 托管 `frontend-vue/dist` 构建产物。
- Java 后端：继续监听 `127.0.0.1:8080`，只负责 `/api/**`。
- 旧 HTML：放到 `/opt/store-profit/legacy-frontend/`，仅通过 `/legacy/index.html` 只读访问。

## 服务器目录

```text
/opt/store-profit/
├─ backend/
│  └─ store-profit-backend-0.1.0-SNAPSHOT.jar
├─ frontend/
│  ├─ index.html
│  └─ assets/
├─ legacy-frontend/
│  ├─ index.html
│  ├─ database.js
│  └─ cloudbase.full.js
├─ frontend-backup/
├─ backend-backup/
├─ mysql-backup/
└─ logs/
```

## Nginx

配置模板：

```text
docs/nginx-vue3-ai-profit-os.conf
```

访问规则：

- `/` -> Vue3
- `/login` -> Vue3
- `/boss` -> Vue3
- `/finance` -> Vue3
- `/warehouse` -> Vue3
- `/inspection` -> Vue3
- `/operations` -> Vue3
- `/todos` -> Vue3
- `/api/**` -> Spring Boot
- `/legacy/index.html` -> 旧 HTML

关键点：

- Vue3 使用 history 路由，Nginx 必须 `try_files $uri $uri/ /index.html`。
- `/api/` 必须写在 `/` 前面，避免接口被前端路由拦截。
- `index.html` 使用 `no-cache`，避免上线后浏览器仍读取旧入口。

## 发布 Vue3

本地构建：

```powershell
cd frontend-vue
npm run build
```

一键上传：

```powershell
.\scripts\deploy-vue3-frontend.ps1 -Server 服务器IP -PublicUrl "http://服务器IP:18080/"
```

脚本行为：

- 执行 `npm run build`。
- 打包 `frontend-vue/dist`。
- 上传到 `/opt/store-profit/frontend/`。
- 上传前备份旧版本到 `/opt/store-profit/frontend-backup/yyyyMMdd-HHmmss/`。
- 不执行 Maven。
- 不重启 Java。

## 回滚 Vue3

列出备份：

```powershell
.\scripts\rollback-vue3-frontend.ps1 -Server 服务器IP -List
```

恢复最近一次备份：

```powershell
.\scripts\rollback-vue3-frontend.ps1 -Server 服务器IP -PublicUrl "http://服务器IP:18080/"
```

恢复指定备份：

```powershell
.\scripts\rollback-vue3-frontend.ps1 -Server 服务器IP -BackupName 20260709-103000
```

回滚不需要重启 Java。

## 发布后端

只有修改 Java、SQL 迁移或 Maven 依赖时，才需要发布后端。

本地构建：

```powershell
cd backend
mvn -q test
mvn -q -DskipTests package
```

服务器备份旧 jar：

```bash
mkdir -p /opt/store-profit/backend-backup/$(date +%Y%m%d-%H%M%S)
cp /opt/store-profit/backend/store-profit-backend-0.1.0-SNAPSHOT.jar \
   /opt/store-profit/backend-backup/$(date +%Y%m%d-%H%M%S)/
```

上传并重启：

```powershell
scp backend/target/store-profit-backend-0.1.0-SNAPSHOT.jar root@服务器IP:/opt/store-profit/backend/
ssh root@服务器IP "systemctl restart store-profit && curl -f http://127.0.0.1:8080/api/health"
```

后端回滚流程见：

```text
docs/production-backup-restore.md
```

## 旧 HTML 只读入口

旧系统不删除，建议放到：

```text
/opt/store-profit/legacy-frontend/
```

访问地址：

```text
http://服务器IP:18080/legacy/index.html
```

旧系统页面顶部应提示：

```text
当前系统已使用 Vue3 正式版。旧版仅供历史回看，不支持新增或修改业务数据。
```

生产默认入口必须是 Vue3。旧 HTML 只允许通过 `/legacy/index.html` 只读访问，不能被后端根路径直接托管。

## 默认首页

登录成功后：

- 老板 -> `/boss`
- 财务 -> `/finance`
- 仓库管理员 -> `/warehouse`
- 督导 -> `/inspection`
- 店长 -> `/warehouse`
- 运营 -> `/operations`

未登录访问业务路由跳转 `/login`。

无权限访问显示无权限页，不直接白屏。

## 验证清单

桌面端打开：

```text
http://127.0.0.1:5173/login
http://127.0.0.1:5173/boss
http://127.0.0.1:5173/finance
http://127.0.0.1:5173/warehouse
http://127.0.0.1:5173/inspection
http://127.0.0.1:5173/operations
http://127.0.0.1:5173/todos
```

手机宽度检查：

- 375px
- 390px
- 430px
- 768px

检查点：

- 没有整页横向溢出。
- 顶部菜单按钮能打开和关闭抽屉。
- 当前角色只显示自己的菜单。
- 卡片不重叠。
- 表格可以横向滚动。
- 按钮高度适合触控。
- 刷新 `/boss`、`/finance`、`/warehouse`、`/inspection`、`/operations`、`/todos` 不 404。
- `/api/health` 正常。

账号验证：

- 在隔离的非生产数据库中，为每个角色创建受控验收账号。
- 账号密码只通过部署密钥或密码管理系统提供，不写入仓库、截图和发布记录。
- Web 应用启动和登录接口只验证已存在账号，不提供默认账号、店长账号或首管理员初始化能力。
- R1-02 的受控非 Web 初始化工具交付前，空数据库不能登录；不得以启动参数或 HTTP 接口临时建号。

完整上线验收清单见：

```text
docs/vue3-production-readiness-checklist.md
```

备份与恢复说明见：

```text
docs/production-backup-restore.md
```

# AI Profit OS 历史静态页部署说明

> 本文仅保留旧 HTML 回退与历史排查信息，不是正式发布指南。正式前端唯一入口为 `frontend-vue`，请使用 `docs/vue3-production-deployment.md`。

## 目标

把前端页面和 Java 后端部署边界分清楚：

- 前端改动：只上传静态文件，刷新浏览器生效。
- 后端改动：执行测试、打包、上传 jar、重启 Java。

Java 后端代码不能像前端一样刷新立即生效。线上推荐用 Nginx 托管前端，Spring Boot 只负责 `/api/**`。

## 项目内目录

项目根目录的历史静态页目录：

```text
runtime-static/
├─ index.html
├─ database.js
└─ cloudbase.full.js
```

说明：

- `runtime-static/index.html` 仅用于旧系统只读回看或紧急回退。
- `backend/src/main/resources/static/index.html` 是历史兼容副本，不是 jar 的正式入口。
- 线上正式入口必须由 Nginx 托管 `frontend-vue/dist`；旧 HTML 只允许通过 `/legacy/` 路由访问。

当前 Spring Boot 静态资源读取顺序：

```text
file:./runtime-static/
file:../runtime-static/
classpath:/static/
```

如果从 `backend` 目录启动，系统会通过 `../runtime-static/` 找到项目根目录的外置页面。

## 本地开发

启动后端：

```powershell
cd backend
$env:MYSQL_PASSWORD='<本机MySQL密码>'
java -jar target/store-profit-backend-0.1.0-SNAPSHOT.jar
```

只改前端时：

```text
直接修改 runtime-static/index.html
刷新 http://127.0.0.1:8080/index.html
```

不需要：

```powershell
mvn -q -DskipTests package
Stop-Process / 重启 Java
```

如果修改了 `database.js` 或 `cloudbase.full.js`，也放在 `runtime-static/` 里更新即可。

## 本地自动刷新，可选

需要保存文件后浏览器自动刷新时：

```powershell
npx browser-sync start --proxy "127.0.0.1:8080" --files "runtime-static/**/*" --port 3000
```

以后打开：

```text
http://127.0.0.1:3000/index.html
```

## 线上推荐目录

服务器建议结构：

```text
/opt/store-profit/
├─ backend/
│  └─ store-profit-backend-0.1.0-SNAPSHOT.jar
├─ frontend/
│  ├─ index.html
│  ├─ database.js
│  └─ cloudbase.full.js
└─ logs/
```

Nginx 读取：

```text
/opt/store-profit/frontend/
```

Spring Boot 只监听本机：

```text
127.0.0.1:8080
```

## Nginx 配置

配置模板见：

```text
docs/nginx-ai-profit-os.conf
```

核心规则：

```text
访问 /index.html 和静态文件 -> /opt/store-profit/frontend/
访问 /api/** -> http://127.0.0.1:8080/api/**
```

前端文件设置 `no-cache`，避免上传后浏览器仍使用旧页面。

## 只更新前端

只改页面时，上传静态文件：

```powershell
scp runtime-static/index.html root@服务器IP:/opt/store-profit/frontend/index.html
```

如果改了前端脚本，也同步上传：

```powershell
scp runtime-static/database.js root@服务器IP:/opt/store-profit/frontend/database.js
scp runtime-static/cloudbase.full.js root@服务器IP:/opt/store-profit/frontend/cloudbase.full.js
```

然后刷新浏览器即可。

不需要：

- `mvn package`
- 上传 jar
- 重启 Java
- 重启 MySQL

## 一键上传脚本

项目已提供：

```text
scripts/deploy-frontend.ps1
```

先检查，不真正上传：

```powershell
.\scripts\deploy-frontend.ps1 -Server 服务器IP -DryRun
```

默认只上传 `runtime-static/index.html`：

```powershell
.\scripts\deploy-frontend.ps1 -Server 服务器IP -PublicUrl "http://服务器IP:18080/index.html"
```

同时上传 `index.html`、`database.js`、`cloudbase.full.js`：

```powershell
.\scripts\deploy-frontend.ps1 -Server 服务器IP -All -PublicUrl "http://服务器IP:18080/index.html"
```

指定上传某几个文件：

```powershell
.\scripts\deploy-frontend.ps1 -Server 服务器IP -Files index.html,database.js
```

脚本行为：

- 上传目标目录：`/opt/store-profit/frontend/`
- 上传前自动备份旧文件到：`/opt/store-profit/frontend-backup/yyyyMMdd-HHmmss/`
- 不执行 `mvn package`
- 不重启 Java

如果服务器用户名不是 `root`：

```powershell
.\scripts\deploy-frontend.ps1 -Server 服务器IP -User ubuntu
```

如果服务器目录不是 `/opt/store-profit`：

```powershell
.\scripts\deploy-frontend.ps1 -Server 服务器IP -RemoteRoot "/data/store-profit"
```

## 前端回滚脚本

项目已提供：

```text
scripts/rollback-frontend.ps1
```

列出最近 20 个前端备份：

```powershell
.\scripts\rollback-frontend.ps1 -Server 服务器IP -List
```

恢复最近一次备份：

```powershell
.\scripts\rollback-frontend.ps1 -Server 服务器IP -PublicUrl "http://服务器IP:18080/index.html"
```

恢复指定备份：

```powershell
.\scripts\rollback-frontend.ps1 -Server 服务器IP -BackupName 20260708-180000
```

回滚脚本只复制静态文件，不重启 Java。

## 更新后端

只有改这些内容时，才需要后端发布：

- Java 后端代码
- Controller / Service / Repository
- 数据库迁移脚本
- Maven 依赖
- `application.yml`

后端发布命令：

```powershell
cd backend
mvn -q test
mvn -q -DskipTests package
scp target/store-profit-backend-0.1.0-SNAPSHOT.jar root@服务器IP:/opt/store-profit/backend/
ssh root@服务器IP "systemctl restart store-profit"
```

## 部署验证

本地验证：

```powershell
Invoke-WebRequest http://127.0.0.1:8080/index.html
Invoke-WebRequest http://127.0.0.1:8080/api/health
```

本地热更新验证：

```powershell
# 1. 临时在 runtime-static/index.html 加一个明显标记
# 2. 不打包、不重启 Java
# 3. 刷新 http://127.0.0.1:8080/index.html
# 4. 确认标记出现
# 5. 移除标记后再次刷新确认恢复
```

线上验证：

```bash
curl -I http://服务器IP:18080/index.html
curl -I http://服务器IP:18080/api/health
```

预期：

- `/index.html` 返回前端页面。
- `/api/health` 返回后端接口响应。
- `/api/**` 不会被前端 `try_files` 路由拦截。

线上热更新验证：

```text
1. 临时在 runtime-static/index.html 加一个页面标记。
2. 执行 scripts/deploy-frontend.ps1 上传 index.html。
3. 不重启 Java。
4. 刷新 http://服务器IP:18080/index.html，确认标记出现。
5. 移除标记，再执行上传脚本。
6. 刷新页面，确认标记消失。
7. 访问 http://服务器IP:18080/api/health，确认接口正常。
```

如果页面没有变化，按顺序检查：

- Nginx `root` 是否指向 `/opt/store-profit/frontend`。
- 当前浏览器地址是否访问 Nginx 端口，而不是 Java 8080 端口。
- `curl -I /index.html` 是否看到 `Cache-Control: no-cache, must-revalidate`。
- 服务器 `/opt/store-profit/frontend/index.html` 是否确实已更新。
- Nginx 配置是否已加载：`nginx -t && systemctl reload nginx`。
- 是否仍由 jar 内置静态资源提供页面。

## 回滚方式

前端回滚：

```powershell
scp 上一个版本的index.html root@服务器IP:/opt/store-profit/frontend/index.html
```

后端回滚：

```bash
cp /opt/store-profit/backend/store-profit-backend-上一个版本.jar /opt/store-profit/backend/store-profit-backend-0.1.0-SNAPSHOT.jar
systemctl restart store-profit
```

## 结论

你不能让云服务器自动知道本地改了文件，但可以做到：

```text
前端只上传静态文件，刷新就生效。
后端只有改 Java、SQL、依赖时才打包重启。
```

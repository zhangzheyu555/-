# 生产部署版本更新记录

**日期**：2026-07-18
**版本**：首次生产部署（QA 环境）
**部署目标**：腾讯云轻量应用服务器 OpenCloudOS 9.6（x86_64）
**正式访问地址**：`https://ruguotea.cn/admin/`

---

## 一、本次部署概览

本次将系统首次部署到腾讯云生产服务器，并通过备案域名 `ruguotea.cn` 以 HTTPS 方式对外提供服务。部署采用 Docker Compose 单机多容器架构，共 7 个服务容器协同运行。同时完成微信小程序接入的域名前置配置。

| 维度 | 结果 |
| --- | --- |
| 部署方式 | Docker Compose（单机 7 容器） |
| 公网入口 | Nginx gateway 容器，宿主机 80/443 |
| HTTPS | 腾讯云免费证书（TrustAsia DV，覆盖 `ruguotea.cn` 和 `www.ruguotea.cn`） |
| 数据库初始数据 | 导入演示联调包（V68，102 表，47 账号） |
| 验证状态 | 6 个业务容器全部 healthy；浏览器登录通过 |

---

## 二、服务架构

```text
用户浏览器 / 微信小程序
  │
  │  https://ruguotea.cn
  ▼
腾讯云轻量服务器（OpenCloudOS 9.6, 4C3.6G + 5G Swap）
  │
  │  宿主机 80/443
  ▼
Docker Compose · gateway（nginx:1.27-alpine）
  ├── /healthz      健康检查
  ├── /             官网静态目录（official-site）
  ├── /admin/       frontend 容器（Vue3 dist + nginx）
  ├── /api/         backend 容器（Spring Boot 8080）
  └── /train-img/   backend 容器（培训图片）

依赖容器：
  ├── mysql           8.4（业务主库）
  ├── redis           8（缓存/会话）
  ├── postgres        17（辅助库）
  └── inspection-service  Python + torch（卫生识别，amd64 本地构建镜像）
```

容器端口绑定策略：除 gateway 外，所有容器端口只绑定 `127.0.0.1`，不对外暴露。gateway 绑定 `0.0.0.0:80` 和 `0.0.0.0:443`，是唯一公网入口。

---

## 三、本次变更内容

### 3.1 新增 Docker 化部署能力（从旧项目迁移并补全）

从历史项目 `--codex-deepseek-assistant` 迁移并补全了 Docker 部署所需的全部配置，使整套系统可以在 Docker Compose 中一键拉起。

新增文件：

| 文件 | 用途 |
| --- | --- |
| `docker-compose.yml` | 编排入口，定义 7 个服务 |
| `.env.example` | 环境变量模板（端口、密码、镜像 tag） |
| `backend/Dockerfile`、`backend/.dockerignore` | 后端镜像（产物模式，见 3.2） |
| `frontend-vue/Dockerfile`、`frontend-vue/.dockerignore`、`frontend-vue/nginx.conf` | 前端镜像（产物模式） |
| `inspection-service/Dockerfile`、`inspection-service/.dockerignore` | 识别服务镜像 |
| `deploy/nginx-unified.conf` | gateway 统一网关配置（HTTP→HTTPS 跳转 + 路由分发） |
| `deploy/official-site/index.html` | 官网静态占位页 |
| `docker/mysql/init/`、`docker/postgres/init/` | 数据库初始化脚本挂载点 |
| `docs/local-docker-env.md` | 本地 Docker 环境说明 |

修正 `.gitignore`：新增 `!.env.example` 例外规则，使模板文件可纳入版本控制（原 `.env.*` 规则把它误伤），真实 `.env` 仍被忽略，密码不会进仓库。

### 3.2 backend / frontend 改为"产物模式"镜像

原 Dockerfile 在容器内编译源码（backend 跑 Maven、frontend 跑 npm），服务器需要装 JDK/Node 且首次构建慢、依赖下载困难。改为产物模式：

- **backend/Dockerfile**：基础镜像改为 `eclipse-temurin:21-jre-jammy`，直接 COPY 本地构建好的 `target/*.jar`。服务器无需 Maven/JDK。
- **frontend-vue/Dockerfile**：基础镜像改为 `nginx:1.27-alpine`，直接 COPY 本地构建好的 `dist/`。服务器无需 Node/npm。
- 对应 `.dockerignore` 放行 `target/*.jar` 和 `dist/`，忽略源码。

`inspection-service` 因 Python 无编译产物，保留源码构建方式不变。

### 3.3 前端路由 base 修复（解决白屏）

**现象**：浏览器访问 `/admin/` 能加载 HTML、JS、CSS（状态码均 200），但页面空白，`#app` 为空，控制台无报错。

**根因**：`frontend-vue/src/router/index.ts` 使用 `createWebHistory()` 未传 base，默认按 `/` 匹配路由；但实际访问路径是 `/admin/`，Vue Router 匹配不到任何路由，导致不渲染。

**修复**：

```ts
// frontend-vue/src/router/index.ts
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),  // 跟随 Vite 构建的 base
  routes,
})
```

`import.meta.env.BASE_URL` 由 Vite 在构建时按 `--base=/admin/` 注入，路由 base 与资源 base 自动对齐。构建命令保持 `npx vite build --base=/admin/`。

### 3.4 后端 CORS 改为环境变量配置（解决登录 403 Invalid CORS）

**现象**：浏览器登录返回 403，后端响应 `Invalid CORS request`。

**根因**：`backend/src/main/resources/application.yml` 的 `app.cors.allowed-origin-patterns` 硬编码为 `localhost`/`127.0.0.1`/旧服务器 IP，不支持环境变量覆盖，无法适配新域名/IP。

**修复**：

```yaml
# backend/src/main/resources/application.yml
app:
  cors:
    allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:*,http://127.0.0.1:*}
```

同时在 `docker-compose.yml` 的 backend 服务补传环境变量：

```yaml
environment:
  APP_CORS_ALLOWED_ORIGINS: ${APP_CORS_ALLOWED_ORIGINS:-http://localhost:*,http://127.0.0.1:*}
```

此后换 IP/域名只改 `.env` 的 `APP_CORS_ALLOWED_ORIGINS` 并重启 backend 即可，不用重新打包 jar。

服务器实际配置（`.env`）：

```
APP_CORS_ALLOWED_ORIGINS=https://ruguotea.cn,https://www.ruguotea.cn,http://81.70.94.245,http://localhost:*,http://127.0.0.1:*
```

### 3.5 HTTPS 与域名配置

**域名**：`ruguotea.cn`（已 ICP 备案，指向本服务器）。

**DNS**：在 DNSPod 添加 A 记录，`ruguotea.cn` → 服务器公网 IP。

**SSL 证书**：腾讯云 SSL 控制台申请免费 DV 证书（TrustAsia C1），覆盖 `ruguotea.cn` 和 `www.ruguotea.cn`。证书文件（`ruguotea.cn_bundle.crt` + `ruguotea.cn.key`）部署到服务器 `/root/store-profit/ssl/`。

**Nginx HTTPS 配置**（`deploy/nginx-unified.conf`）：
- 80 端口：保留 `/healthz` 健康检查，其余请求 `301` 跳转到 `https://$host$request_uri`。
- 443 端口：启用 SSL（TLS 1.2/1.3），加载证书，配置 HSTS（`Strict-Transport-Security`，1 年）。
- `docker-compose.yml` 的 gateway：新增 `443:443` 端口映射，新增 `./ssl:/etc/nginx/ssl:ro` 证书目录挂载。

**端口**：`.env` 中 `HTTP_PORT=80`，gateway 实际暴露 `0.0.0.0:80->80` 和 `0.0.0.0:443->443`。腾讯云防火墙和宝塔防火墙均放行 80 和 443。

### 3.6 数据库演示数据导入

**数据包**：`store_profit_mysql8_qa_latest_package_20260718_104623.sql`（Flyway V1→V68 空库 + 演示基础数据）。

**处理**：
- 将 SQL 中的库名 `store_profit_mysql8_qa_latest_package_20260718_104623` 统一替换为 `store_profit_qa`（与 compose 配置一致），通过 `sed` 在服务器上原地修改后导入。
- 导入方式：`docker exec -i mysql mysql -uroot ... < dump.sql`。
- 导入前先 `docker compose stop backend`，避免导入过程中后端连接干扰。

**导入结果**（对照 `verification-counts.txt` 验证）：

| 校验项 | 期望 | 实际 |
| --- | --- | --- |
| 表数量 | 102 | 102 |
| 账号数 | 47 | 47 |
| 门店数 | 12 | 12 |
| 员工档案 | 30 | 30 |
| 损耗项配置 | 60 | 60 |

所有演示账号密码统一为 `12345678`，含 `boss`/`finance`/`supervisor`/`warehouse`/`operations` + 12 个店长 + 30 个员工。

### 3.7 服务器环境准备

| 项目 | 配置 |
| --- | --- |
| Docker | 28.0.1 + Compose 2.32.1（已预装） |
| Swap | 原有 1G + 新增 4G（`/swapfile`），共 5G；写入 `/etc/fstab` 开机自启；`vm.swappiness=10` |
| Docker 镜像加速 | 腾讯云内网 `mirror.ccs.tencentyun.com` |
| Docker 日志限制 | `json-file`，`max-size=50m`，`max-file=3`（防爆盘） |
| 防火墙 | 腾讯云安全组 + 宝塔防火墙均放行 80、443；22 仅管理员 IP |

**inspection-service 镜像**：因服务器无 GPU 且国内拉取 PyTorch 困难，采用本地 Mac（Apple Silicon）跨架构构建 `linux/amd64` 镜像，`docker save` + 分片上传 + 服务器 `docker load` 方式部署。构建时替换 apt 源为清华镜像加速，torch 走 PyTorch 官方 CPU 仓库。

---

## 四、部署过程踩坑与决策记录

本节记录部署过程中遇到的关键问题与最终决策，便于后续复盘和同类问题排查。

### 4.1 PyTorch 下载卡死（CUDA 包过大 + 国外源）

- **现象**：inspection-service 在服务器上构建时，`pip install torch==2.5.1` 卡死在下载（磁盘零增长、CPU 闲置）。
- **原因**：默认装 CUDA 版 torch（2-4GB NVIDIA 包），服务器无 GPU 白白浪费；且国内直连 PyPI 国外节点对大文件下载容易 TCP 挂起。
- **尝试**：换清华 PyPI（小包快，但大文件仍偶发挂起）→ 换 PyTorch 官方 CPU 仓库（国外源，服务器上仍慢）。
- **最终方案**：本地 Mac 用 `docker buildx --platform linux/amd64` 跨架构构建，导出后上传。本地能顺畅访问官方源，一次成功。

### 4.2 arm64/amd64 架构不匹配导致容器启动失败

- **现象**：首次上传的 inspection 镜像启动报 `requested image's platform (linux/arm64) does not match host platform (linux/amd64)`，容器 unhealthy。
- **原因**：Mac Apple Silicon 默认构建 arm64 镜像，服务器是 x86_64。
- **解决**：`docker buildx build --platform linux/amd64` 重新构建 amd64 镜像。

### 4.3 宝塔单文件 500MB 上传限制

- **现象**：导出的 inspection 镜像 tar.gz 约 613MB，超过宝塔免费版单文件 500MB 限制。
- **解决**：`split -b 400m` 分片为 2 个文件分别上传，服务器 `cat` 合并后 `docker load`。

### 4.4 前端白屏（见 3.3）

### 4.5 登录 403 Invalid CORS（见 3.4）

### 4.6 数据库名不一致（见 3.6）

---

## 五、微信小程序接入

小程序与 PC 管理端**共用同一套后端 API**（`https://ruguotea.cn/api/`），不维护第二套后端。

**接入要点**：

1. **request 合法域名**：必须在微信公众平台 → 开发管理 → 开发设置 → 服务器域名 → request 合法域名 中添加 `https://ruguotea.cn`（不带端口、路径、结尾斜杠）。
   - 真机调试不校验合法域名，所以真机能登录；**体验版和正式版严格校验**，不配会被微信在客户端直接拦截。
2. **uploadFile / downloadFile 合法域名**：如果小程序用到拍照识别、附件上传下载，同样要在对应栏目加 `https://ruguotea.cn`。
3. **HTTPS 强制**：微信小程序只允许 HTTPS，已满足。
4. **域名刷新**：服务器域名修改后，已发布的体验版/正式版不会自动刷新，需要重新上传发版才生效。

**CORS**：微信小程序的 `wx.request` 是原生网络请求，不走浏览器 CORS 机制，因此不会被后端 CORS 配置拦截（已在服务器验证：不带 Origin 请求 `/api/health` 返回 200）。

---

## 六、涉及文件清单

本次部署改动/新增的文件（不含 `mobile-uniapp` 目录的独立小程序迭代）：

**新增**：
- `docker-compose.yml`、`.env.example`
- `backend/Dockerfile`、`backend/.dockerignore`
- `frontend-vue/Dockerfile`、`frontend-vue/.dockerignore`、`frontend-vue/nginx.conf`
- `inspection-service/Dockerfile`、`inspection-service/.dockerignore`
- `deploy/nginx-unified.conf`、`deploy/official-site/index.html`
- `docker/mysql/init/README.md`、`docker/postgres/init/README.md`
- `docs/local-docker-env.md`、`docs/release-2026-07-18-production-deployment.md`（本文档）

**修改**：
- `.gitignore`：新增 `!.env.example` 例外
- `backend/src/main/resources/application.yml`：CORS 改为环境变量
- `frontend-vue/src/router/index.ts`：路由 base 改为 `import.meta.env.BASE_URL`

**服务器端配置（不进仓库）**：
- `/root/store-profit/.env`：实际环境变量（含密码、域名、CORS 白名单）
- `/root/store-profit/ssl/`：SSL 证书文件

---

## 七、已知风险与后续建议

### 7.1 安全（上线前必做）

1. **弱密码**：当前所有演示账号密码统一为 `12345678`，数据库 root 密码、Redis/Postgres 密码均为 `.env.example` 默认弱值。**正式上线前必须全部改为强密码**。
2. **演示数据**：数据库当前含演示门店、员工、损耗配置，按 `AGENTS.md` 规则，**生产环境不得保留示例业务数据**。上线前应清空演示数据，只保留账号骨架或由管理员重建正式账号。
3. **CORS 来源**：测试阶段 `.env` 的 `APP_CORS_ALLOWED_ORIGINS` 包含 `http://81.70.94.245` 明文 IP，上线收紧后建议只保留 `https://ruguotea.cn`。

### 7.2 性能与稳定性

1. **内存**：服务器 3.6G 内存跑 7 个容器（含 inspection 的 torch）偏紧，目前靠 5G Swap 顶住启动峰值。inspection 加载模型时较慢（Swap 比内存慢）。建议稳定后升级到 **8G 内存**。
2. **torch GPU 版浪费**：inspection 镜像里目前装的是 CPU 版 torch（约 200MB），符合无 GPU 现状。如未来加 GPU，需单独构建 GPU 版镜像。
3. **日志**：已配 Docker 日志轮转（50m×3），长期运行不会撑爆磁盘。

### 7.3 备份

1. **MySQL 备份**：目前无自动备份。建议增加定时 `mysqldump` 任务，备份到 `/root/store-profit/backup/` 或对象存储。
2. **卷数据**：`mysql-data`、`redis-data`、`postgres-data`、`backend-expense-supplements`、`inspection-data` 是 Docker 命名卷，迁移时需一并备份。

### 7.4 架构演进

1. **WAF**：流量稳定后接入腾讯云 WAF，DNS 改为 CNAME 接入，收紧源站 80/443 只允许 WAF 回源 IP。
2. **官方官网**：根路径 `/` 当前是占位页，未来把正式官网构建产物放入 `deploy/official-site/` 即可，无需改 nginx。
3. **证书续期**：腾讯云免费证书有效期 1 年，到期前在 SSL 控制台续签并替换 `/root/store-profit/ssl/` 下的文件。

---

## 八、验证记录

| 验证项 | 方法 | 结果 |
| --- | --- | --- |
| 6 容器健康 | `docker compose ps` | 全部 `Up (healthy)` |
| HTTP→HTTPS 跳转 | `curl -sI http://ruguotea.cn/` | `301 Moved Permanently` |
| HTTPS 首页 | `curl -sI https://ruguotea.cn/` | `200` |
| 后台管理页 | `curl -sI https://ruguotea.cn/admin/` | `200` |
| 后端健康检查 | `curl -s https://ruguotea.cn/api/health` | `{"success":true,...,"status":"UP"}` |
| 识别服务健康 | `curl -s http://127.0.0.1:8000/health` | `{"ok":true,...}` |
| 浏览器登录 | `boss` / `12345678` | 成功进入系统 |
| 数据库表/账号数 | `SELECT COUNT(*)` | 102 表 / 47 账号（与数据包一致） |

---

## 九、运维速查

服务器目录：`/root/store-profit/`

```bash
# 查看服务状态
docker compose ps

# 查看某服务实时日志
docker compose logs -f backend
docker compose logs -f gateway

# 重启单个服务
docker compose restart backend

# 停止 / 启动全部
docker compose down
docker compose up -d

# 修改 .env 后让 backend 生效
docker compose up -d backend

# 进入 MySQL
docker exec -it codex-deepseek-assistant-mysql mysql -uroot -p

# 查看 SSL 证书到期时间
echo | openssl s_client -connect ruguotea.cn:443 -servername ruguotea.cn 2>/dev/null | openssl x509 -noout -dates
```

**修改 CORS 白名单（新增域名/IP）**：

```bash
cd /root/store-profit
# 编辑 .env 里的 APP_CORS_ALLOWED_ORIGINS 行
vi .env
# 重启 backend 生效
docker compose up -d backend
```

**修改 nginx 配置后**：

```bash
cd /root/store-profit
docker compose restart gateway
```

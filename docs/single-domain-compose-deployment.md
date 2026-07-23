# 单域名同机部署方案

官网静态页、PC 管理端、后端 API、MySQL、Redis、Postgres 和巡检识别服务全部部署在同一台自有服务器。正式域名统一使用 `www.heima.cn`，由宿主机 Nginx 负责 HTTPS 证书和公网 80/443 入口，Docker Compose 内的 `gateway` 负责应用路径分流。

## 目标访问路径

| 路径 | 服务 |
| --- | --- |
| `https://www.heima.cn/` | 官网静态页面 |
| `https://www.heima.cn/admin/` | PC 管理端 |
| `https://www.heima.cn/api/` | PC 管理端和小程序共用后端接口 |

小程序和 PC 管理端共用 `/api/`，不要再维护第二套小程序专用后端。小程序正式真机只需要在微信公众平台配置 `https://www.heima.cn` 为 request 合法域名。

## 网络结构

```text
用户/微信小程序
  |
  | https://www.heima.cn
  v
业务服务器 Nginx :443
  |
  | http://127.0.0.1:18080
  v
Docker Compose gateway
  |-- /admin/        PC 管理端容器
  |-- /              官网静态目录
  `-- /api/          Spring Boot 后端容器
```

## 部署前服务器准备

1. 准备一台 OpenCloudOS 9 服务器，至少 2C4G，生产建议 4C8G 起。
2. `www.heima.cn` 的 A 记录解析到业务服务器公网 IP。
3. 安全组/防火墙只开放公网 `80/tcp`、`443/tcp`、`22/tcp`。后端 `8080`、前端 `5173`、MySQL `3306`、Redis `6379`、Postgres `5432`、识别服务 `8000` 和 Compose 网关 `18080` 不对公网开放。
4. 安装 Docker Engine 和 Docker Compose Plugin。
5. 安装宿主机 Nginx 与 Certbot，用于签发和自动续期 `www.heima.cn` 的 HTTPS 证书。
6. 准备官网静态页面构建产物。如果官网是独立项目，记下官网 `dist` 的绝对路径，后续填入 `OFFICIAL_SITE_ROOT`。
7. 准备生产密码：MySQL root 密码、MySQL 应用账号密码、Redis 密码、Postgres 密码、后端第三方平台密钥、AI 密钥。
8. 准备数据库备份策略，至少保留每日备份和发布前手工备份。

OpenCloudOS 9 示例：

```bash
sudo dnf makecache
sudo dnf install -y yum-utils curl ca-certificates nginx firewalld
sudo dnf install -y certbot python3-certbot-nginx

sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo systemctl enable --now docker
sudo systemctl enable --now nginx
sudo systemctl enable --now firewalld

sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --permanent --add-service=ssh
sudo firewall-cmd --reload

sudo usermod -aG docker $USER
docker version
docker compose version
```

执行 `usermod` 后重新登录 SSH，让当前用户获得 Docker 权限。

OpenCloudOS 9 默认可能启用 SELinux。建议先保持 `Enforcing`，如果 Nginx 反代到本机 `127.0.0.1:18080` 被 SELinux 拦截，执行：

```bash
sudo setsebool -P httpd_can_network_connect 1
```

不要为了省事直接关闭 SELinux。除非已经确认是 SELinux 策略问题且短期无法修复，否则生产环境保持开启更稳。

## 本地运行

```bash
cp .env.example .env
docker compose up -d --build
```

本地默认地址：

| 地址 | 用途 |
| --- | --- |
| `http://127.0.0.1:8088/` | 官网 |
| `http://127.0.0.1:8088/admin/` | PC 管理端 |
| `http://127.0.0.1:8088/api/health` | 后端健康检查 |

如果本地 8088 被占用，修改 `.env`：

```env
HTTP_PORT=18080
```

## 服务器环境变量

服务器上复制 `.env.example` 为 `.env`，至少修改以下内容：

```env
APP_ENV=PRODUCTION

GATEWAY_HTTP_BIND_IP=127.0.0.1
HTTP_PORT=18080
OFFICIAL_SITE_ROOT=/opt/heima-official-site/dist
VITE_PUBLIC_BASE=/admin/

MYSQL_DATABASE=store_profit_prod
MYSQL_ROOT_PASSWORD=替换为强密码
MYSQL_APP_USER=store_profit_app
MYSQL_APP_PASSWORD=替换为强密码
MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL=false

REDIS_PASSWORD=替换为强密码

POSTGRES_DB=store_profit_aux
POSTGRES_USER=store_profit_pg
POSTGRES_PASSWORD=替换为强密码

APP_SEED_DEMO_ENABLED=false
APP_BOOTSTRAP_DEFAULT_USERS_ENABLED=false
APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED=false

DEEPSEEK_ENABLED=false
DEEPSEEK_API_KEY=
```

说明：

- `GATEWAY_HTTP_BIND_IP=127.0.0.1` 表示 Compose 网关只监听服务器本机，由宿主机 Nginx 转发，避免绕过 HTTPS。
- `OFFICIAL_SITE_ROOT` 指向官网静态页面构建产物目录。若暂时没有官网，默认 `./deploy/official-site` 会显示占位页。
- 生产环境不要开启默认账号、演示数据或固定密码。

## 启动应用

```bash
docker compose pull
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:18080/healthz
curl -fsS http://127.0.0.1:18080/api/health
```

后端、前端、数据库、Redis、识别服务和应用网关都应为 `healthy`。

## 配置 HTTPS 入口

先签发证书：

```bash
sudo certbot certonly --nginx -d www.heima.cn
```

创建宿主机 Nginx 配置：

```nginx
server {
    listen 80;
    server_name www.heima.cn;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name www.heima.cn;

    ssl_certificate /etc/letsencrypt/live/www.heima.cn/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/www.heima.cn/privkey.pem;

    client_max_body_size 65m;

    location / {
        proxy_pass http://127.0.0.1:18080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }
}
```

保存为 `/etc/nginx/conf.d/ai-profit-os.conf` 后执行：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

验证：

```bash
curl -I https://www.heima.cn/
curl -fsS https://www.heima.cn/api/health
curl -I https://www.heima.cn/admin/
```

## 官网部署

官网为静态页面，部署到业务服务器本地目录，并由 Compose 网关的 `/` 路径提供访问。

如果官网是独立项目，先在官网项目中构建：

```bash
npm ci
npm run build
```

然后把 `.env` 中的 `OFFICIAL_SITE_ROOT` 指到官网构建目录，例如：

```env
OFFICIAL_SITE_ROOT=/opt/heima-official-site/dist
```

重启网关即可：

```bash
docker compose up -d gateway
```

## PC 管理端部署

PC 管理端由当前项目 `frontend-vue` 构建，部署在 `/admin/`。Compose 已通过 `VITE_PUBLIC_BASE=/admin/` 构建前端，页面内静态资源会使用 `/admin/assets/...`。

更新代码后重新构建：

```bash
docker compose build frontend gateway
docker compose up -d frontend gateway
```

## 后端和数据库部署

后端容器只监听 Docker 网络和服务器本机 `127.0.0.1:8080`，公网统一通过 `https://www.heima.cn/api/` 访问。数据库数据保存在 Docker volume 中：

| Volume | 用途 |
| --- | --- |
| `mysql-data` | 业务 MySQL 数据 |
| `redis-data` | Redis 数据 |
| `postgres-data` | 辅助 Postgres 数据 |
| `backend-expense-supplements` | 报销补充附件 |
| `inspection-data` | 巡检识别服务数据 |

发布前备份 MySQL：

```bash
docker compose exec mysql sh -lc 'mysqldump --no-tablespaces -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' > backup-before-release.sql
```

## 小程序配置

小程序接口 baseURL 使用：

```text
https://www.heima.cn/api
```

微信公众平台配置：

- request 合法域名：`https://www.heima.cn`
- uploadFile/downloadFile 合法域名：`https://www.heima.cn`
- 证书必须是可信 CA 证书，不能使用自签证书或 IP HTTPS。

## 发布验收清单

1. `docker compose ps` 全部核心服务健康。
2. `https://www.heima.cn/` 打开官网静态页。
3. `https://www.heima.cn/admin/` 打开 PC 管理端，刷新二级路由不 404。
4. `https://www.heima.cn/api/health` 返回 `success=true` 或健康状态。
5. 未登录访问受保护接口返回 401。
6. 不同角色登录后只能看到和操作各自权限范围。
7. 店长跨门店访问返回 403。
8. 上传巡检图片、附件、PDF 下载可用，上传大图不被 Nginx 413 拦截。
9. 小程序 Android 和 iPhone 真机均可登录、请求 `/api/health`、上传图片。
10. 服务器公网端口扫描确认只有 80、443、22 暴露。

## 常用运维命令

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f gateway
docker compose restart backend
docker compose up -d --build
docker system df
```

## 回滚思路

1. 保留上一个镜像 tag，不要发布后立即清理 Docker image。
2. 发布前必须导出 MySQL 备份。
3. 回滚应用时切回上一个代码版本或镜像 tag，再执行 `docker compose up -d`。
4. 如果 Flyway 已执行新迁移，数据库回滚必须先评估迁移是否可逆；生产不允许直接修改已执行迁移文件。

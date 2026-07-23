# 本地 Docker 环境

本项目提供本地开发用的 `docker-compose.yml`，包含前端、Java 后端、Python 巡检服务、MySQL 8、Redis 和 PostgreSQL。

## 版本

- MySQL: `mysql:8.4`
- Redis: `redis:8`
- PostgreSQL: `postgres:17`
- Backend: 本地构建 `backend/Dockerfile`，运行 Spring Boot 8080
- Inspection service: 本地构建 `inspection-service/Dockerfile`，运行 FastAPI 8000
- Frontend: 本地构建 `frontend-vue/Dockerfile`，Nginx 提供 Vue 静态文件

这些镜像标签可以在本机 `.env` 中覆盖。不要使用 `latest`，避免同一份环境在不同时间启动出不同版本。

## 启动

复制环境变量样例：

```bash
cp .env.example .env
```

按需修改 `.env` 里的本地密码，然后启动：

```bash
docker compose up -d
```

查看状态：

```bash
docker compose ps
```

首次构建完整环境：

```bash
docker compose build
docker compose up -d
```

只重建业务服务：

```bash
docker compose build backend inspection-service frontend
docker compose up -d backend inspection-service frontend
```

停止环境：

```bash
docker compose down
```

如果要清空本地数据库卷并重新初始化：

```bash
docker compose down -v
```

## 连接信息

MySQL:

- Host: `127.0.0.1`
- Port: `${MYSQL_HOST_PORT:-3306}`
- Database: `${MYSQL_DATABASE:-store_profit_qa}`
- Root user: `root`
- App user: `${MYSQL_APP_USER:-store_profit_app}`

Redis:

- Host: `127.0.0.1`
- Port: `${REDIS_HOST_PORT:-6379}`
- Password: `${REDIS_PASSWORD:-store_profit_redis}`

PostgreSQL:

- Host: `127.0.0.1`
- Port: `${POSTGRES_HOST_PORT:-5432}`
- Database: `${POSTGRES_DB:-store_profit_aux}`
- User: `${POSTGRES_USER:-store_profit_pg}`

业务服务:

- Frontend: `http://127.0.0.1:${FRONTEND_PORT:-5173}`
- Backend: `http://127.0.0.1:${BACKEND_PORT:-8080}`
- Backend health: `http://127.0.0.1:${BACKEND_PORT:-8080}/api/health`
- Inspection service: `http://127.0.0.1:${INSPECTION_PORT:-8000}`
- Inspection health: `http://127.0.0.1:${INSPECTION_PORT:-8000}/health`

前端容器通过 Nginx 把 `/api/` 和 `/train-img/` 转发到 `backend:8080`，后端容器通过 `INSPECTION_DETECT_URL` 和 `INSPECTION_EXPORT_URL` 访问 `inspection-service:8000`。

## 后端使用 MySQL 容器

本项目后端有环境守卫，本地 Docker Compose 默认使用 `APP_ENV=QA` 和 `MYSQL_DATABASE=store_profit_qa`。如果不使用 Docker 启动 Java 后端，请显式设置完整数据库环境变量：

```bash
cd backend
APP_ENV=QA MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_DATABASE=store_profit_qa MYSQL_USERNAME=store_profit_app MYSQL_PASSWORD=store_profit_app MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL=true mvn spring-boot:run
```

不要在本地开发以 `STAGING` 或 `PRODUCTION` 运行容器内后端；这两个环境会要求固定的受控数据库端点。


## 导入已有 MySQL Dump

把 dump 导入当前 MySQL 容器：

```bash
docker compose exec -T mysql mysql -uroot -pstore_profit_root < /path/to/store_profit_mysql8_dump.sql
```

如果 dump 内部创建并使用的是 `store_profit_mysql8`，后端启动时需要指定：

```bash
cd backend
MYSQL_DATABASE=store_profit_mysql8 MYSQL_USERNAME=root MYSQL_PASSWORD=store_profit_root mvn spring-boot:run
```

注意：导入 dump 可能覆盖同名库或表。导入生产导出前，先确认本地卷里没有需要保留的数据。

## 本地账号种子

默认不创建固定账号、默认密码或示例业务数据。确实需要本地空库快速登录时，可以只在本机 `.env` 中打开：

```bash
APP_BOOTSTRAP_DEFAULT_USERS_ENABLED=true
APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD=<本机临时密码>
```

不要把本机 `.env` 提交到仓库。

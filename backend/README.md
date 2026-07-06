# 门店利润系统后端

Java Spring Boot 后端，第一阶段提供 MySQL 持久化、兼容现有前端的 `/api/storage` 接口，以及受限在本系统内的 DeepSeek 数据助手接口。

## 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x

## 创建数据库

如果当前 MySQL 账号有建库权限，后端会自动创建 `store_profit`。也可以手动创建：

```sql
create database if not exists store_profit
  default character set utf8mb4
  default collate utf8mb4_unicode_ci;
```

## 配置数据库

后端默认连接：

- host: `localhost`
- port: `3306`
- database: `store_profit`
- username: `root`
- password: 空

可以用环境变量覆盖：

```powershell
$env:MYSQL_HOST="localhost"
$env:MYSQL_PORT="3306"
$env:MYSQL_DATABASE="store_profit"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的密码"
```

## 启动

```powershell
mvn spring-boot:run
```

启动成功后访问：

- `http://localhost:8080/api/health`
- `http://localhost:8080/api/storage?key=stores`
- `http://localhost:8080/api/assistant/chat`

## DeepSeek 数据助手配置

数据助手接口不会把 API Key 暴露给前端。把 Key 配在后端环境变量里：

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
$env:DEEPSEEK_MODEL="deepseek-v4-flash"
```

未配置 `DEEPSEEK_API_KEY` 时，接口会返回缺 Key 提示，前端数据助手会自动回退到本地规则查询。

可选配置：

```powershell
$env:DEEPSEEK_BASE_URL="https://api.deepseek.com"
$env:DEEPSEEK_MAX_TOKENS="800"
$env:DEEPSEEK_TEMPERATURE="0.2"
```

## 第一阶段接口

### 读取兼容存储

```http
GET /api/storage?key=stores
```

响应：

```json
{
  "value": "[JSON string or null]"
}
```

### 写入兼容存储

```http
POST /api/storage
Content-Type: application/json

{
  "key": "stores",
  "value": "[JSON string]"
}
```

### 数据助手

```http
POST /api/assistant/chat
Content-Type: application/json

{
  "message": "保利店5月营业额是多少",
  "history": [],
  "dataContext": "品牌,门店,月份,营业总收入,净利..."
}
```

后端会先做系统范围判断和屏蔽词校验，只允许回答门店利润系统相关问题。

## 前端连接方式

如果后续把前端交给后端托管并从 `http://localhost:8080` 打开，会同源请求 `/api/storage`。

如果前端从其他本地端口打开，例如 `http://localhost:5500`，`database.js` 会默认请求 `http://localhost:8080/api/storage`。

如果直接双击 `index.html` 打开，仍使用原本的本地回退逻辑。

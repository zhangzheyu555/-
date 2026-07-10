# Java 后端迁移阶段 1 实施计划

日期：2026-07-06

## 阶段目标

搭建 `backend/` Spring Boot 后端骨架，接入 MySQL，并提供兼容现有前端 `sGet` / `sSet` 的 `/api/storage` 接口。前端页面暂时保留，只调整数据访问优先级，使通过 HTTP 打开时优先读写后端。

## 实施步骤

1. 创建后端项目结构。
   - 新增 `backend/pom.xml`。
   - 新增 Spring Boot 启动类。
   - 新增基础包：`common`、`config`、`storage`、`health`。

2. 配置 MySQL 和 Flyway。
   - `application.yml` 使用环境变量读取数据库连接。
   - 默认数据库名：`store_profit`。
   - 新增 `V1__init_schema.sql`，创建 `kv_storage` 和第一阶段需要的业务表。

3. 实现统一响应和异常处理。
   - `ApiResponse<T>`：统一成功/失败格式。
   - `GlobalExceptionHandler`：处理未捕获异常。

4. 实现兼容存储接口。
   - `GET /api/storage?key=...`
   - `POST /api/storage`
   - 表：`kv_storage(storage_key, storage_value, updated_at)`。
   - `storage_value` 存 JSON 字符串，兼容现有前端。

5. 加入基础健康检查。
   - `GET /api/health`
   - 用于确认后端已启动。

6. 配置 CORS。
   - 开发期允许 `http://localhost:*` 和 `http://127.0.0.1:*`。
   - 方便前端独立打开或由静态服务器打开时调用后端。

7. 调整前端数据访问。
   - 修改 `database.js` 中 `sGet` / `sSet`。
   - 通过 HTTP 打开时优先请求 `/api/storage`。
   - 后端不可用时回退 CloudBase / window.storage / localStorage。
   - 直接双击 `index.html` 时仍使用原本回退逻辑。

8. 更新 README。
   - 补充后端启动命令。
   - 补充 MySQL 数据库创建说明。
   - 补充前端如何连接后端。

9. 验证。
   - 执行 `mvn -q -DskipTests package`。
   - 如果 MySQL 已配置，执行后端启动验证。
   - 确认 JavaScript 内联脚本语法仍通过。

## 第一阶段交付物

- `backend/` Spring Boot 后端项目。
- MySQL/Flyway 建表脚本。
- `/api/storage` 兼容接口。
- `/api/health` 健康检查接口。
- 前端 `database.js` 后端优先读写。
- README 后端启动说明。

## 暂不做

- 不重写前端 UI。
- 不迁移所有前端业务计算。
- 不做 JWT 登录。
- 不把现有 JSON 拆入业务表。
- 不接入复杂 AI 数据助手。

这些留到阶段 2 和阶段 3。

## 验收标准

- 后端 Maven 构建成功。
- 数据库表结构脚本完整。
- `/api/storage` 接口可编译通过。
- 前端仍保留本地回退能力。
- 现有 `index.html` 不因本次改动产生语法错误。

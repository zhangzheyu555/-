# legacy kv_storage 收口说明

日期：2026-07-17

本说明记录第三阶段对 `kv_storage`、`StorageService.get/set` 和 `/api/storage` 调用点的收口结果。正式业务数据源仍以结构化 MySQL 表为准，`kv_storage` 只保留历史迁移、诊断提醒和显式开发/演示种子用途。

## 保留的迁移/诊断路径

- `backend/src/main/java/com/storeprofit/system/storage/StorageController.java`
  - `GET /api/storage`、`POST /api/storage` 仍保留为历史兼容入口。
  - 调用 `StorageService.get(AuthUser, key)` / `StorageService.set(AuthUser, key, value)`。
  - 入口先从认证上下文取得用户，再由 `AccessControlService.requireLegacyStorageAccess` 要求 `system.migration.manage` 权限。
- `backend/src/main/java/com/storeprofit/system/migration/MigrationStatusService.java`
  - 读取 `kv_storage` 用于 legacy KV 状态、预览和一次性结构化迁移。
  - 浏览器存储迁移写入仍会经过 `StorageService.set(user, ...)`，因此继续受迁移权限控制。
  - 迁移执行会写结构化业务表和迁移操作日志，不作为常规业务查询源。
- `backend/src/main/java/com/storeprofit/system/todo/RoleTodoRepository.java`
  - 只查询 `migration_error:%`、`import_error:%`、`legacy_error:%` 键，生成数据健康待办。
  - 不从 KV 读取利润、巡检、仓库等正式业务实体。

## 仅 dev/demo 显式 seed 路径

- `backend/src/main/java/com/storeprofit/system/organization/OrganizationRepository.java`
  - `kv("stores")` 只服务 `OrganizationSeedService.seedStoresFromLegacyJson()`。
  - `OrganizationSeedService` 已移除自动 `@Service` / `@PostConstruct`，只应在显式迁移或开发演示种子场景调用。
- `backend/src/main/java/com/storeprofit/system/finance/FinanceSeedService.java`
  - 已移除自动 Spring Bean 注册。
  - 仅在显式 seed 或 `app.migration.auto-run` 场景读取 legacy `entries`。

## 正式业务路径扫描结果

- `frontend-vue/src` 未发现 `GET /api/storage?key=...` 或 `POST /api/storage` 的 KV 业务调用。
- `frontend-vue/src/api/finance.ts`、`frontend-vue/src/api/inspection.ts` 调用的是 `/api/storage/upload` 和 `/api/storage/attachments/{id}`，属于附件上传/下载，不是 KV 数据源。
- `frontend-vue` 中 `localStorage` 只用于登录 token、登录用户和记住用户名，不保存正式业务数据。
- 当前未发现需要从 KV 迁移到结构化 MySQL 的正式业务页面或正式业务 Controller。

## 验收点

- 普通业务角色不能读写 `/api/storage` KV：`StorageServiceTest.ordinaryRoleCannotReadOrWriteLegacyKvWithoutMigrationPermission`。
- legacy KV 写入会记录 `legacy_storage_write` 操作日志。
- `/api/storage/attachments/{id}` 是附件闭环接口，权限和审计由 `StorageService.attachment` 处理，不属于 KV 读写。

## 后续守门规则

- 新业务页面不得调用 `GET /api/storage` 或 `POST /api/storage`。
- 新业务 Controller 不得直接读取 `kv_storage`。
- 如必须导入历史浏览器数据，应走 `/api/migration/**` 或 `/api/storage` 的迁移权限路径，并在完成后落到结构化 MySQL 表。

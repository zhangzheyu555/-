# Flyway 迁移版本规则

## 适用范围

从 `V106` 开始，MySQL 与 H2 的新增 Flyway 迁移必须使用：

```text
V<主版本>_<yyyyMMddHHmmssSSS>__<英文说明>.sql
```

示例：

```text
V106_20260727143015842__add_inventory_alert_index.sql
```

Flyway 将该文件记录为版本 `106.20260727143015842`。时间戳是版本的一部分，用于避免并行分支重复创建同一个主版本号。

历史 `V0` 至 `V105` 文件继续使用原有 `V<主版本>__<英文说明>.sql` 格式。它们可能已经在环境中执行，严禁修改文件名、内容或校验和。

## 新建迁移

在仓库根目录执行：

```powershell
.\scripts\new-flyway-migration.ps1 -Name add_inventory_alert_index
```

脚本会根据当前最新主版本生成上海时区、毫秒精度的时间戳，并同时创建 MySQL 与 H2 两个同版本模板。填写 SQL 后一起提交。

## 校验要求

- MySQL 与 H2 最新迁移文件名必须一致。
- 新的主版本必须大于 `105`，且必须包含 17 位时间戳。
- 同一目录不得存在重复的完整 Flyway 版本。
- GitHub CI 会在空 MySQL 库启动应用，并以 `flyway_schema_history.installed_rank` 的最后一条记录核对完整版本。
- 发布清单使用完整字符串版本；不得将 `106.20260727143015842` 转为整数。

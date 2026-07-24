# 2026-07-23 生产环境完整更新手册

## 目标与边界

- 更新 PC 管理端和后端。
- 使用 `ai_profit_os_real_qa-full-20260723` 完全替换生产业务数据。
- 小程序不由服务器前端包更新，需使用 `miniprogram-update.tar.gz` 在微信开发者工具中单独上传发行。
- 不在原生产库上覆盖：先恢复到影子库 `store_profit_qa_20260723`，验证后切换。
- 原生产库和旧镜像至少保留 7 天。
- `qmai_platform_config`、`platform_account` 属于生产环境配置，切换时保留旧生产值；登录令牌不保留。

## 一、需要上传到服务器的文件

上传到 `/root/store-profit/release-20260723/`：

```text
backend-update.tar.gz
frontend-update.tar.gz
ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-aa
ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-ab
ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-ac
SHA256SUMS
```

上传完成后：

```bash
cd /root/store-profit/release-20260723
sha256sum -c SHA256SUMS
```

三个数据库上传分片与合并流的正确 SHA-256：

```text
86b5d4af0340c8c108ef13be9de38edde5a0f9bee564db0b97819df31f91f938  ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-aa
a8092031e54777e2da5390303066a16c07b73aae080c2d0ceca6a03f499226cb  ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-ab
bdd5c04b5ead8a11c75f3319d19b8e5c3429876cbcc382043c8312a51cf0ce71  ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-ac
```

校验合并流：

```bash
cat ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-aa \
    ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-ab \
    ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-ac \
  | sha256sum
```

应得到：

```text
231b6ba581a85aced97245dced4260926c387de18b8ea5679a320ee91c400122  -
```

## 二、上线前检查

```bash
cd /root/store-profit
docker compose ps
curl -fsS https://ruguotea.cn/api/health
df -h
grep '^MYSQL_DATABASE=' .env
```

磁盘建议至少预留 6 GB。记录健康接口中的：

- `sourceVersion`
- `databaseMigrationVersion`
- `databaseName`

当前生产库名称以下用 `store_profit_qa` 表示；若 `.env` 实际值不同，后续命令中的旧库名要同步替换。

## 三、备份旧生产库和生产专用配置

```bash
cd /root/store-profit
mkdir -p backups/release-20260723

docker compose exec -T mysql sh -lc \
'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --events "$MYSQL_DATABASE"' \
| gzip > backups/release-20260723/store_profit_qa-before-replacement.sql.gz

docker compose exec -T mysql sh -lc \
'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --no-create-info --complete-insert "$MYSQL_DATABASE" qmai_platform_config platform_account' \
> backups/release-20260723/production-environment-config.sql

gzip -t backups/release-20260723/store_profit_qa-before-replacement.sql.gz
ls -lh backups/release-20260723
```

不要把备份、`.env`、数据库密码和平台密钥提交到 Git。

## 四、创建并恢复影子库

```bash
cd /root/store-profit

docker compose exec -T mysql sh -lc \
'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
DROP DATABASE IF EXISTS store_profit_qa_20260723;
CREATE DATABASE store_profit_qa_20260723
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON store_profit_qa_20260723.*
  TO '"'"'$MYSQL_USER'"'"'@'"'"'%'"'"';
FLUSH PRIVILEGES;
"'

cat release-20260723/ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-aa \
    release-20260723/ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-ab \
    release-20260723/ai_profit_os_real_qa-full-20260723.sql.gz.upload-part-ac \
| gzip -dc \
| docker compose exec -T mysql sh -lc \
'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 store_profit_qa_20260723'
```

说明：`DROP DATABASE` 只针对本次新建的固定影子库，绝不能替换成当前生产库名。

## 五、规范影子库迁移历史并恢复生产配置

源快照中版本 93–95 的 Flyway 记录来自另一套脚本命名；同时快照导出期间 V90 正在执行，历史表已记录 V90，但较早导出的 `profit_entry` 表结构尚未包含 V90 字段。发布包已补齐正式的 V93–V95，因此删除影子库内 V90、V93–V97 记录，再由新后端按正式脚本重新执行。V93–V95 对已有结构均为幂等执行。

```bash
cd /root/store-profit

docker compose exec -T mysql sh -lc \
'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" store_profit_qa_20260723 -e "
DELETE FROM flyway_schema_history
WHERE version IN (90, 93, 94, 95, 96, 97);

DELETE FROM auth_token;

SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM qmai_platform_config;
DELETE FROM platform_account;
SET FOREIGN_KEY_CHECKS = 1;
"'

docker compose exec -T mysql sh -lc \
'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 store_profit_qa_20260723' \
< backups/release-20260723/production-environment-config.sql
```

本次完整替换会采用快照内的账号、角色、门店、微信绑定和业务数据；旧登录会话全部失效，用户需重新登录。若生产微信 AppID 与快照不同，切换后需重新绑定账号。

## 六、切换前校验影子库

```bash
cd /root/store-profit

docker compose exec -T mysql sh -lc \
'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" store_profit_qa_20260723 -e "
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = DATABASE();

SELECT COUNT(*) AS account_count FROM auth_user;
SELECT COUNT(*) AS store_count FROM store_branch;
SELECT COUNT(*) AS employee_count FROM employee;
SELECT COUNT(*) AS video_count FROM training_video;
SELECT COUNT(*) AS knowledge_document_count FROM knowledge_base_document;
SELECT COUNT(*) AS token_count FROM auth_token;

SELECT version, description, script, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 15;
"'
```

必须确认：

- 表数量不少于 113。
- `auth_token` 为 0。
- 视频 44 条、知识库文档 49 条。
- Flyway 已执行记录全部 `success=1`。

## 七、更新代码产物并预留镜像回滚点

```bash
cd /root/store-profit

docker image tag codex-deepseek-assistant-backend:local \
  codex-deepseek-assistant-backend:rollback-20260723
docker image tag codex-deepseek-assistant-frontend:local \
  codex-deepseek-assistant-frontend:rollback-20260723

cp backend/target/store-profit-backend-0.1.0-SNAPSHOT.jar \
  backups/release-20260723/store-profit-backend-before-replacement.jar

tar -xzf release-20260723/backend-update.tar.gz

mv frontend-vue/dist \
  backups/release-20260723/frontend-dist-before-replacement
tar -xzf release-20260723/frontend-update.tar.gz -C frontend-vue

docker compose build backend frontend
```

在不影响当前线上 backend 的情况下，用一次性容器先对影子库执行迁移：

```bash
docker compose run -d --name store-profit-backend-shadow-check \
  --no-deps \
  -e MYSQL_DATABASE=store_profit_qa_20260723 \
  backend

docker logs store-profit-backend-shadow-check --tail=200
docker inspect --format '{{.State.Health.Status}}' \
  store-profit-backend-shadow-check
```

待状态为 `healthy` 后验证迁移版本：

```bash
docker compose exec -T mysql sh -lc \
'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" store_profit_qa_20260723 -e "
SELECT version, description, script, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 10;
"'

docker rm -f store-profit-backend-shadow-check
```

必须看到 V90–V97 均为 `success=1`，否则不要执行正式切换。

## 八、正式切换

```bash
cd /root/store-profit

cp .env backups/release-20260723/env-before-replacement
sed -i.bak \
  's/^MYSQL_DATABASE=.*/MYSQL_DATABASE=store_profit_qa_20260723/' \
  .env

docker compose stop backend frontend
docker compose up -d backend
docker compose ps backend
docker compose logs backend --tail=200
```

等待 backend 显示 healthy，再启动前端：

```bash
docker compose up -d frontend gateway
docker compose ps

curl -fsS https://ruguotea.cn/api/health
curl -fsSI https://ruguotea.cn/admin/
```

健康接口应显示：

```text
databaseName: store_profit_qa_20260723
databaseMigrationVersion: 97
status: UP
```

随后人工验证：

1. 老板、财务、督导、店长、仓库账号各登录一次。
2. 检查门店、员工、工资、报损、叫货、库存、培训视频和知识库。
3. 验证新增、保存、提交、审批等写操作。
4. 检查平台配置能否正常读取；敏感值不得在页面或日志中明文出现。

## 九、回滚

若切换后验证失败：

```bash
cd /root/store-profit

docker compose stop backend frontend
cp backups/release-20260723/env-before-replacement .env

docker image tag codex-deepseek-assistant-backend:rollback-20260723 \
  codex-deepseek-assistant-backend:local
docker image tag codex-deepseek-assistant-frontend:rollback-20260723 \
  codex-deepseek-assistant-frontend:local

docker compose up -d --no-build backend frontend gateway
docker compose ps
curl -fsS https://ruguotea.cn/api/health
```

回滚后健康接口的 `databaseName` 必须重新显示旧生产库。不要删除影子库，以便排查。

## 十、观察期

- 旧生产库、旧镜像、旧 jar、旧前端和备份至少保留 7 天。
- 观察期内每天检查磁盘、容器健康、后端错误日志和 MySQL 错误日志。
- 7 天后如需清理，先再次确认当前 `.env` 指向影子库且备份可恢复，再单独制定清理清单。

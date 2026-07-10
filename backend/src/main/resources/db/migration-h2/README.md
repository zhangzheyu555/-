# H2 本地迁移（仅本地开发用）

`db/migration/` 下的生产迁移大量使用 MySQL 动态 SQL（`set @sql := if(...)` + `prepare`），H2 无法解析。
本目录是它们的**语义等价 H2 转写版**（V0 额外定义 `date_format` 函数别名），只给本地 `local` profile（H2 内存库）使用。

启用方式：`application-local.yml`（被 gitignore，需自建）里加：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:store_profit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
    username: sa
    password: ""
    driver-class-name: org.h2.Driver
  flyway:
    locations: classpath:db/migration-h2
```

本地启动还需要引导环境变量（空库没有任何用户）：

```bash
APP_BOOTSTRAP_DEFAULT_USERS_ENABLED=true APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD=<密码> \
APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED=true APP_BOOTSTRAP_STORE_MANAGER_PASSWORD=<密码> \
APP_SEED_DEMO_ENABLED=true \
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

**维护约定：给 `db/migration/` 加新迁移时，必须同步在本目录加对应的 H2 版本**（无 MySQL 动态语法时可直接复制）。

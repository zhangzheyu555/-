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

R1-01 起，本地 Web 应用启动和登录接口都不会创建业务账号。空库会保持 `auth_user` 为空；
在 R1-02 的受控非 Web 初始化工具交付前，空库不提供登录能力。认证相关开发测试必须显式准备测试夹具，
不得通过应用启动参数创建默认账号或店长账号。

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

**维护约定：给 `db/migration/` 加新迁移时，必须同步在本目录加对应的 H2 版本**（无 MySQL 动态语法时可直接复制）。

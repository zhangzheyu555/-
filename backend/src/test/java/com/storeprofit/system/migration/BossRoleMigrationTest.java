package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class BossRoleMigrationTest {
  @Test
  void migrationUnifiesLegacyHighestRolesAndRemovesConflictingScopes() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:boss-role-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table auth_user(id bigint primary key, tenant_id bigint not null, role varchar(40) not null, store_id varchar(64), password_hash varchar(255) not null)");
    jdbc.execute("create table user_store_scope(id bigint auto_increment primary key, tenant_id bigint not null, user_id bigint not null, store_id varchar(64) not null)");
    jdbc.execute("create table role_permission(id bigint auto_increment primary key, tenant_id bigint not null, role_code varchar(40) not null, permission_code varchar(120) not null)");
    jdbc.update("insert into auth_user values (1, 1, 'ADMIN', 'rg1', 'hash-a'), (2, 1, 'OWNER', 'rg2', 'hash-o'), (3, 1, 'BOSS', null, 'hash-b'), (4, 1, 'FINANCE', null, 'hash-f')");
    jdbc.update("insert into user_store_scope(tenant_id, user_id, store_id) values (1, 1, 'rg1'), (1, 2, 'rg2'), (1, 4, 'rg3')");
    jdbc.update("insert into role_permission(tenant_id, role_code, permission_code) values (1, 'ADMIN', 'all'), (1, 'OWNER', 'all'), (1, 'BOSS', 'all')");

    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration-h2/V31__unify_boss_role.sql"))
        .execute(dataSource);

    assertThat(jdbc.queryForObject(
        "select count(*) from auth_user where role in ('ADMIN', 'OWNER')", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from auth_user where role = 'BOSS'", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject(
        "select count(*) from user_store_scope where user_id in (1, 2, 3)", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from user_store_scope where user_id = 4", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from role_permission where role_code in ('ADMIN', 'OWNER')", Integer.class)).isZero();
    assertThat(jdbc.queryForList(
        "select password_hash from auth_user order by id", String.class))
        .containsExactly("hash-a", "hash-o", "hash-b", "hash-f");
  }
}

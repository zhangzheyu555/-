package com.storeprofit.system.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InventoryCheckReviewWorkflowMigrationTest {
  @Test
  void v106ConvertsDraftsPreservesCancelledHistoryBackfillsReviewerAndGrantsReview() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(("""
        jdbc:h2:mem:inventory-review-workflow-%s;
        MODE=MySQL;
        DATABASE_TO_LOWER=TRUE;
        CASE_INSENSITIVE_IDENTIFIERS=TRUE;
        DB_CLOSE_DELAY=-1;
        NON_KEYWORDS=MONTH,YEAR,DAY,VALUE
        """).formatted(UUID.randomUUID()).replaceAll("\\s+", ""));
    dataSource.setUser("sa");
    dataSource.setPassword("");
    migrate(dataSource, "105");

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update("""
        insert into store_branch(id, tenant_id, code, name, status, created_at)
        values ('V106-INVENTORY-STORE', 1, 'V106-INVENTORY', 'V106 盘存门店', '营业中',
          current_timestamp)
        """);
    jdbc.update("""
        insert into auth_user(
          id, tenant_id, username, password_hash, display_name, role,
          enabled, permission_version, created_at
        ) values (10601, 1, 'v106-inventory-finance', 'hash', '历史财务负责人', 'FINANCE',
          1, 3, current_timestamp)
        """);
    jdbc.update("""
        insert into auth_token(
          token_hash, tenant_id, user_id, permission_version, expires_at, created_at
        ) values ('1060000000000000000000000000000000000000000000000000000000000001',
          1, 10601, 3, timestamp '2099-01-01 00:00:00', current_timestamp)
        """);
    jdbc.update("""
        insert into store_inventory_check(
          tenant_id, check_no, store_id, store_name, check_date, status,
          total_amount, submitted_by, reviewed_by, reviewed_at, created_by,
          created_at, updated_at
        ) values
          (1, 'PDC-V106-DRAFT', 'V106-INVENTORY-STORE', 'V106 盘存门店', current_date,
            'DRAFT', 10, null, null, null, 10601, current_timestamp, current_timestamp),
          (1, 'PDC-V106-CANCELLED', 'V106-INVENTORY-STORE', 'V106 盘存门店', current_date,
            'CANCELLED', 20, 10601, null, null, 10601, current_timestamp, current_timestamp),
          (1, 'PDC-V106-REVIEWED', 'V106-INVENTORY-STORE', 'V106 盘存门店', current_date,
            'REVIEWED', 30, 10601, 10601, current_timestamp, 10601,
            current_timestamp, current_timestamp)
        """);

    migrate(dataSource, "106");

    assertThat(jdbc.queryForMap("""
        select status, submitted_by
        from store_inventory_check
        where check_no = 'PDC-V106-DRAFT'
        """)).containsEntry("status", "SUBMITTED").containsEntry("submitted_by", 10601L);
    assertThat(jdbc.queryForObject("""
        select status
        from store_inventory_check
        where check_no = 'PDC-V106-CANCELLED'
        """, String.class)).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForMap("""
        select reviewed_by_name, reviewed_by_role
        from store_inventory_check
        where check_no = 'PDC-V106-REVIEWED'
        """))
        .containsEntry("reviewed_by_name", "历史财务负责人")
        .containsEntry("reviewed_by_role", "FINANCE");

    jdbc.update("""
        insert into store_inventory_check(
          tenant_id, check_no, store_id, store_name, check_date, total_amount, created_at
        ) values (1, 'PDC-V106-DEFAULT', 'V106-INVENTORY-STORE', 'V106 盘存门店',
          current_date, 0, current_timestamp)
        """);
    assertThat(jdbc.queryForObject("""
        select status
        from store_inventory_check
        where check_no = 'PDC-V106-DEFAULT'
        """, String.class)).isEqualTo("SUBMITTED");
    assertThat(jdbc.queryForList("""
        select role_code
        from role_permission
        where tenant_id = 1
          and permission_code = 'inventory.review'
          and upper(role_code) in ('FINANCE', 'SUPERVISOR', 'WAREHOUSE')
        order by role_code
        """, String.class)).containsExactly("FINANCE", "SUPERVISOR", "WAREHOUSE");
    assertThat(jdbc.queryForObject(
        "select permission_version from auth_user where id = 10601", Long.class)).isEqualTo(4L);
    assertThat(jdbc.queryForObject(
        "select count(*) from auth_token where user_id = 10601", Integer.class)).isZero();
  }

  private void migrate(JdbcDataSource dataSource, String target) {
    assertThat(Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration-h2")
        .target(target)
        .load()
        .migrate()
        .success).isTrue();
  }
}

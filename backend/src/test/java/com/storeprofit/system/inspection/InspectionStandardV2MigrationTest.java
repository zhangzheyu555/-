package com.storeprofit.system.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class InspectionStandardV2MigrationTest {
  @Test
  void createsMilkTeaStandardWithoutChangingHistoricalSnapshot() {
    DataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:inspection-v2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        create table inspection_standard_version (
          id bigint auto_increment primary key, tenant_id bigint not null, version varchar(64) not null,
          title varchar(160) not null, full_score decimal(8,2) not null, effective_date date,
          status varchar(24) not null, created_by bigint, created_at timestamp default current_timestamp,
          updated_at timestamp, unique (tenant_id, version)
        )
        """);
    jdbc.execute("""
        create table inspection_standard_item (
          id bigint auto_increment primary key, tenant_id bigint not null, standard_version_id bigint not null,
          dimension varchar(120) not null, code varchar(80), title varchar(500) not null, description text,
          suggested_score decimal(8,2), red_line tinyint, enabled tinyint, sort_order int,
          created_at timestamp default current_timestamp, updated_at timestamp
        )
        """);
    jdbc.execute("""
        create table inspection_record_standard_snapshot (
          id bigint auto_increment primary key, tenant_id bigint not null, inspection_record_id varchar(120) not null,
          standard_title varchar(500), standard_description text
        )
        """);
    jdbc.update("""
        insert into inspection_standard_version(tenant_id, version, title, full_score, effective_date, status)
        values (1, '2026-v1', '旧标准', 100, '2026-01-01', 'ACTIVE')
        """);
    jdbc.update("""
        insert into inspection_record_standard_snapshot(tenant_id, inspection_record_id, standard_title, standard_description)
        values (1, 'INSP-HISTORY', '生熟分开存放', '历史判定说明')
        """);

    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration/V32__milk_tea_inspection_standard_v2.sql")
    ).execute(dataSource);
    Long originalItemId = jdbc.queryForObject("""
        select i.id from inspection_standard_item i
        join inspection_standard_version v on v.id = i.standard_version_id
        where v.version = '2026-v2' and i.code = 'M03'
        """, Long.class);

    new ResourceDatabasePopulator(
        new ClassPathResource("db/migration-h2/V33__update_food_storage_inspection_standard.sql")
    ).execute(dataSource);

    assertThat(jdbc.queryForObject(
        "select count(*) from inspection_standard_version where tenant_id = 1 and status = 'ACTIVE' and version = '2026-v2'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from inspection_standard_item i join inspection_standard_version v on v.id = i.standard_version_id where v.version = '2026-v2'",
        Integer.class)).isEqualTo(12);
    assertThat(jdbc.queryForObject("""
        select concat(i.title, '|', i.description) from inspection_standard_item i
        join inspection_standard_version v on v.id = i.standard_version_id
        where v.version = '2026-v2' and i.code = 'M03'
        """, String.class)).isEqualTo(
        "食品原料与非食品用品分区存放|茶叶、糖浆、奶制品、水果、珍珠等食品原料及成品配料应分类密封存放；清洁剂、消毒剂和私人物品必须设置独立区域，不得与食品同柜混放。");
    assertThat(jdbc.queryForObject("""
        select i.id from inspection_standard_item i
        join inspection_standard_version v on v.id = i.standard_version_id
        where v.version = '2026-v2' and i.code = 'M03'
        """, Long.class)).isEqualTo(originalItemId);
    assertThat(jdbc.queryForObject("""
        select count(*) from inspection_standard_item i
        join inspection_standard_version v on v.id = i.standard_version_id
        where v.status = 'ACTIVE' and i.enabled = 1
          and regexp_like(concat(coalesce(i.title, ''), coalesce(i.description, '')), '生熟|生肉|熟肉')
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select standard_title from inspection_record_standard_snapshot where inspection_record_id = 'INSP-HISTORY'",
        String.class)).isEqualTo("生熟分开存放");
  }
}

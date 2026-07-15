package com.storeprofit.system.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeprofit.system.common.BusinessException;
import com.storeprofit.system.platform.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RoleTodoServiceTest {
  private RoleTodoService service;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=MONTH;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("""
        create table brand (
          id bigint not null primary key,
          tenant_id bigint not null,
          name varchar(120) not null
        )
        """);
    jdbcTemplate.execute("""
        create table kv_storage (
          storage_key varchar(120) not null primary key,
          storage_value clob not null,
          updated_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table store_branch (
          id varchar(64) not null primary key,
          tenant_id bigint not null,
          brand_id bigint null,
          code varchar(80) null,
          name varchar(160) not null
        )
        """);
    jdbcTemplate.execute("""
        create table inspection_record (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          inspection_date date not null,
          inspector varchar(120) null,
          full_score decimal(8,2) not null default 200,
          score decimal(8,2) not null default 200,
          passed tinyint(1) not null default 1,
          result_code varchar(32) null,
          redlines_json text null,
          note text null,
          updated_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table inspection_result_repair_audit (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          inspection_record_id varchar(120) not null,
          original_standard_version_id bigint null,
          original_standard_version varchar(64) null,
          original_full_score decimal(8,2) not null,
          original_pass_score decimal(8,2) null,
          original_score decimal(8,2) not null,
          original_material_score decimal(8,2) null,
          original_hygiene_score decimal(8,2) null,
          original_service_score decimal(8,2) null,
          original_result_code varchar(32) null,
          original_passed tinyint(1) not null,
          repaired_standard_version_id bigint not null,
          repaired_standard_version varchar(64) null,
          repaired_full_score decimal(8,2) null,
          repaired_pass_score decimal(8,2) null,
          repaired_score decimal(8,2) null,
          repaired_material_score decimal(8,2) null,
          repaired_hygiene_score decimal(8,2) null,
          repaired_service_score decimal(8,2) null,
          repaired_result_code varchar(32) null,
          repaired_passed tinyint(1) null,
          repair_status varchar(32) not null,
          repair_reason varchar(500) not null,
          snapshot_item_count int not null default 0,
          expected_item_count int not null default 0,
          repaired_by bigint null,
          repaired_at timestamp not null default current_timestamp,
          unique(tenant_id, inspection_record_id, repaired_standard_version_id)
        )
        """);
    jdbcTemplate.execute("""
        create table store_requisition (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          status varchar(40) not null,
          total_amount decimal(14,2) not null default 0,
          note text null,
          submitted_by bigint null,
          reviewed_by bigint null,
          shipped_by bigint null,
          submitted_at timestamp not null default current_timestamp,
          reviewed_at timestamp null,
          shipped_at timestamp null,
          updated_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_item (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          name varchar(160) not null,
          unit varchar(40) not null default '件',
          daily_usage_estimate decimal(14,2) not null default 0,
          min_stock_days int not null default 0,
          min_stock_quantity decimal(14,2) not null default 0,
          alert_enabled tinyint(1) not null default 1,
          expiry_alert_days int null default 3,
          active tinyint(1) not null default 1
        )
        """);
    jdbcTemplate.execute("""
        create table warehouse_stock_batch (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          item_id bigint not null,
          expiry_date date null,
          quantity decimal(14,2) not null default 0
        )
        """);
    jdbcTemplate.execute("""
        create table expense_claim (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          month char(7) null,
          amount decimal(14,2) not null default 0,
          category varchar(80) null,
          reason text null,
          status varchar(40) not null,
          image_url varchar(500) null,
          submitted_by bigint null,
          reviewed_by bigint null,
          reviewed_at timestamp null,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table profit_entry (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          store_id varchar(64) not null,
          month char(7) not null,
          sales decimal(14,2) not null default 0,
          refund decimal(14,2) not null default 0,
          discount decimal(14,2) not null default 0,
          material decimal(14,2) not null default 0,
          packaging decimal(14,2) not null default 0,
          loss decimal(14,2) not null default 0,
          cost_other decimal(14,2) not null default 0,
          rent decimal(14,2) not null default 0,
          labor decimal(14,2) not null default 0,
          utility decimal(14,2) not null default 0,
          property decimal(14,2) not null default 0,
          commission decimal(14,2) not null default 0,
          promo decimal(14,2) not null default 0,
          repair decimal(14,2) not null default 0,
          equip decimal(14,2) not null default 0,
          exp_other decimal(14,2) not null default 0,
          note text null,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table todo_escalation (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          source_role varchar(40) not null,
          source_module varchar(80) not null,
          source_id varchar(120) not null,
          source_todo_id varchar(160) not null,
          reason text not null,
          severity varchar(40) not null,
          reported_by_user_id bigint null,
          reported_by_name varchar(120) null,
          boss_todo_id varchar(160) not null,
          status varchar(40) not null,
          created_at timestamp not null default current_timestamp,
          resolved_at timestamp null
        )
        """);
    jdbcTemplate.execute("""
        create table todo_action (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          todo_id varchar(160) not null,
          action_type varchar(40) not null,
          status varchar(40) not null,
          note text not null,
          actor_user_id bigint null,
          actor_name varchar(120) null,
          actor_role varchar(40) not null,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table todo_action_attachment (
          id varchar(120) not null primary key,
          tenant_id bigint not null,
          action_id varchar(120) not null,
          todo_id varchar(160) not null,
          file_name varchar(240) not null,
          content_type varchar(120) null,
          size_bytes bigint not null,
          content blob not null,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute("""
        create table operation_log (
          id bigint auto_increment primary key,
          tenant_id bigint not null,
          operator_id bigint null,
          operator_name varchar(120) null,
          action varchar(80) not null,
          target_type varchar(80) not null,
          target_id varchar(120) null,
          store_id varchar(64) null,
          month char(7) null,
          reason varchar(255) null,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.update("insert into brand(id, tenant_id, name) values (1, 1, 'Tea'), (2, 2, 'Other')");
    jdbcTemplate.update("""
        insert into store_branch(id, tenant_id, brand_id, code, name)
        values
          ('s1', 1, 1, '001', 'One'),
          ('s2', 1, 1, '002', 'Two'),
          ('other', 2, 2, '099', 'Other')
        """);
    jdbcTemplate.update("""
        insert into inspection_record(id, tenant_id, store_id, inspection_date, inspector, full_score, score, passed, note)
        values
          ('insp-s1', 1, 's1', '2026-07-08', 'Supervisor', 200, 164, 0, 'fix'),
          ('insp-s2-ok', 1, 's2', '2026-07-08', 'Supervisor', 200, 200, 1, 'ok'),
          ('insp-other', 2, 'other', '2026-07-08', 'Other', 200, 100, 0, 'other tenant')
        """);
    jdbcTemplate.update("""
        insert into store_requisition(id, tenant_id, store_id, status, total_amount, submitted_at)
        values
          ('req-s1', 1, 's1', 'SUBMITTED', 120.00, '2099-01-01 10:00:00'),
          ('req-s2-done', 1, 's2', 'TODO_DONE', 80.00, '2099-01-01 10:00:00'),
          ('req-other', 2, 'other', 'SUBMITTED', 999.00, '2099-01-01 10:00:00')
        """);
    jdbcTemplate.update("""
        insert into expense_claim(id, tenant_id, store_id, month, amount, category, reason, status, created_at)
        values
          ('exp-s1', 1, 's1', '2026-07', 88.00, 'repair', 'fix light', '\u5f85\u5ba1\u6838', '2099-01-01 10:00:00'),
          ('exp-s2-done', 1, 's2', '2026-07', 66.00, 'water', 'paid', '\u5df2\u5b8c\u6210', '2099-01-01 10:00:00'),
          ('exp-other', 2, 'other', '2026-07', 999.00, 'other', 'other tenant', '\u5f85\u5ba1\u6838', '2099-01-01 10:00:00')
        """);
    jdbcTemplate.update("""
        insert into profit_entry(
          tenant_id, store_id, month, sales, refund, discount, material, packaging, loss, cost_other,
          rent, labor, utility, property, commission, promo, repair, equip, exp_other, note
        )
        values
          (1, 's1', '2026-07', 1000.00, 0, 0, 600.00, 50.00, 0, 0, 120.00, 130.00, 20.00, 0, 20.00, 20.00, 0, 0, 20.00, 'low margin'),
          (1, 's2', '2026-07', 1000.00, 0, 0, 300.00, 30.00, 0, 0, 80.00, 100.00, 10.00, 0, 10.00, 10.00, 0, 0, 10.00, 'healthy'),
          (2, 'other', '2026-07', 1000.00, 0, 0, 900.00, 50.00, 0, 0, 100.00, 100.00, 0, 0, 0, 0, 0, 0, 0, 'other tenant')
        """);
    RoleTodoRepository repository = new RoleTodoRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    RoleTodoEscalationRepository escalationRepository = new RoleTodoEscalationRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    RoleTodoActionRepository actionRepository = new RoleTodoActionRepository(
        jdbcTemplate,
        new NamedParameterJdbcTemplate(dataSource)
    );
    service = new RoleTodoService(repository, escalationRepository, actionRepository);
  }

  @Test
  void bossSeesTenantScopedInspectionAndWarehouseTodos() {
    RoleTodoResponse response = service.todos(boss(), RoleTodoAudience.BOSS, new RoleTodoQuery(false, null, 50, null, null));

    assertThat(response.roleName()).isEqualTo("老板（系统管理员）");
    assertThat(response.dataSource()).contains("MySQL");
    assertThat(response.items()).extracting(RoleTodoItemResponse::id)
        .contains("profit-risk-s1-2026-07", "inspection-insp-s1", "expense-exp-s1", "warehouse-req-s1");
    assertThat(response.stats()).filteredOn(stat -> "RISK".equals(stat.status()))
        .singleElement()
        .satisfies(stat -> assertThat(stat.count()).isEqualTo(2));
    assertThat(response.stats()).filteredOn(stat -> "PENDING".equals(stat.status()))
        .singleElement()
        .satisfies(stat -> assertThat(stat.count()).isEqualTo(2));
  }

  @Test
  void inspectionTodosUseRepairPresentationAndCanonicalHundredPointConversion() {
    jdbcTemplate.update("""
        insert into inspection_record(
          id, tenant_id, store_id, inspection_date, inspector,
          full_score, score, passed, result_code, redlines_json, note
        ) values
          ('legacy-82-passed', 1, 's1', '2026-07-09', 'Supervisor', 200, 164, 0, 'FAILED', '[]', 'legacy migrated'),
          ('wrong-200-98', 1, 's1', '2026-07-10', 'Supervisor', 200, 98, 1, 'PASSED', '[]', 'wrong result'),
          ('manual-review', 1, 's1', '2026-07-11', 'Supervisor', 200, 190, 1, 'PASSED', '[]', 'incomplete snapshot')
        """);
    jdbcTemplate.update("""
        insert into inspection_result_repair_audit(
          tenant_id, inspection_record_id,
          original_full_score, original_pass_score, original_score,
          original_result_code, original_passed,
          repaired_standard_version_id,
          repaired_full_score, repaired_pass_score, repaired_score,
          repaired_result_code, repaired_passed,
          repair_status, repair_reason, snapshot_item_count, expected_item_count
        ) values
          (1, 'insp-s1', 100, 90, 82, 'FAILED', 0, 40,
           200, 180, 196, 'PASSED', 1,
           'RECALCULATED', '完整快照重新计算', 105, 105),
          (1, 'manual-review', 200, 180, 190, 'PASSED', 1, 40,
           null, null, null, null, null,
           'MANUAL_REVIEW', '快照不完整', 87, 105)
        """);

    RoleTodoResponse response = service.todos(
        supervisor(),
        RoleTodoAudience.SUPERVISOR,
        new RoleTodoQuery(false, null, 50, null, null)
    );

    assertThat(response.items()).extracting(RoleTodoItemResponse::id)
        .doesNotContain("inspection-insp-s1")
        .contains("inspection-legacy-82-passed", "inspection-wrong-200-98", "inspection-manual-review");
    assertThat(response.items()).filteredOn(item -> "inspection-legacy-82-passed".equals(item.id()))
        .singleElement()
        .satisfies(item -> assertThat(item.summary()).contains("164/200"));
    assertThat(response.items()).filteredOn(item -> "inspection-wrong-200-98".equals(item.id()))
        .singleElement()
        .satisfies(item -> assertThat(item.summary()).contains("98/200"));
    assertThat(response.items()).filteredOn(item -> "inspection-manual-review".equals(item.id()))
        .singleElement()
        .satisfies(item -> {
          assertThat(item.title()).contains("待人工复核");
          assertThat(item.processStatus()).isEqualTo("待人工复核");
          assertThat(item.summary()).contains("原始成绩不会被覆盖");
        });
  }

  @Test
  void warehouseAndStoreManagerTodosFollowWarehouseBusinessStatus() {
    jdbcTemplate.update("""
        insert into store_requisition(id, tenant_id, store_id, status, total_amount, submitted_at)
        values
          ('req-approved', 1, 's1', 'APPROVED', 30.00, '2099-01-01 10:00:00'),
          ('req-shipped', 1, 's1', 'SHIPPED', 40.00, '2099-01-01 10:00:00'),
          ('req-received', 1, 's1', 'RECEIVED', 50.00, '2099-01-01 10:00:00'),
          ('req-rejected', 1, 's1', 'REJECTED', 60.00, '2099-01-01 10:00:00')
        """);

    RoleTodoResponse warehouseTodos =
        service.todos(warehouse(), RoleTodoAudience.WAREHOUSE, new RoleTodoQuery(false, null, 50, null, null));
    assertThat(warehouseTodos.items()).extracting(RoleTodoItemResponse::id)
        .contains("warehouse-req-s1", "warehouse-req-approved")
        .doesNotContain("warehouse-req-shipped", "warehouse-req-received", "warehouse-req-rejected");

    RoleTodoResponse storeTodos =
        service.todos(storeManager(), RoleTodoAudience.STORE_MANAGER, new RoleTodoQuery(false, null, 50, null, null));
    assertThat(storeTodos.items()).extracting(RoleTodoItemResponse::id)
        .contains("store-receipt-req-shipped")
        .doesNotContain("warehouse-req-shipped", "warehouse-req-received", "warehouse-req-rejected");
    RoleTodoItemResponse receiptTodo = storeTodos.items().stream()
        .filter(item -> "store-receipt-req-shipped".equals(item.id()))
        .findFirst()
        .orElseThrow();
    assertThat(receiptTodo.title()).isEqualTo("\u5f85\u786e\u8ba4\u6536\u8d27\uff1a\u4ed3\u5e93\u5df2\u53d1\u8d27\uff0c\u8bf7\u786e\u8ba4\u672c\u5e97\u6536\u8d27");
    assertThat(receiptTodo.title()).doesNotContain("\u95e8\u5e97\u53eb\u8d27\u5f85\u5904\u7406");
    assertThat(receiptTodo.summary()).isEqualTo("\u53eb\u8d27\u5355 req-shipped \u5df2\u7531\u4ed3\u5e93\u53d1\u8d27\uff0c\u8bf7\u5230\u4ed3\u5e93\u4e2d\u5fc3\u6838\u5bf9\u5546\u54c1\u548c\u6570\u91cf\u540e\u786e\u8ba4\u6536\u8d27\u3002");
    assertThat(receiptTodo.sourceModule()).isEqualTo("\u4ed3\u5e93\u4e2d\u5fc3");
    assertThat(receiptTodo.processStatus()).isEqualTo("\u5f85\u786e\u8ba4\u6536\u8d27");
    assertThat(receiptTodo.ownerName()).isEqualTo("\u5e97\u957f");
    assertThat(receiptTodo.dueAt()).isEqualTo("\u4eca\u5929\u5185");
    assertThat(receiptTodo.action().label()).isEqualTo("\u53bb\u4ed3\u5e93\u4e2d\u5fc3\u5904\u7406");
  }

  @Test
  void inspectionTodoCannotBeResolvedThroughTheGenericTodoEndpointAndTheRejectionIsAudited() {
    assertThatThrownBy(() -> service.resolve(
        storeManager(),
        RoleTodoAudience.STORE_MANAGER,
        "inspection-insp-s1",
        new RoleTodoCompletionRequest("整改照片已提交", List.of())
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("INSPECTION_RECTIFICATION_WORKFLOW_REQUIRED"));

    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from todo_action where todo_id = 'inspection-insp-s1' and status = 'DONE'",
        Integer.class)).isZero();
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from operation_log
        where action = 'inspection_rectification_legacy_completion_rejected'
          and target_id = 'insp-s1' and store_id = 's1'
        """, Integer.class)).isEqualTo(1);
  }

  @Test
  void bossDashboardKeepsEscalationsActionableAndAggregatesRoleWork() {
    jdbcTemplate.update("insert into store_branch(id, tenant_id, brand_id, code, name) values ('s3', 1, 1, '003', 'Three')");
    jdbcTemplate.update("""
        insert into profit_entry(
          tenant_id, store_id, month, sales, refund, discount, material, packaging, loss, cost_other,
          rent, labor, utility, property, commission, promo, repair, equip, exp_other, note
        )
        values
          (1, 's3', '2026-07', 1000.00, 0, 0, 820.00, 40.00, 0, 0, 80.00, 80.00, 20.00, 0, 10.00, 10.00, 0, 0, 10.00, 'loss')
        """);
    RoleTodoEscalationResponse financeEscalation = service.escalate(
        finance(),
        RoleTodoAudience.FINANCE,
        "expense-exp-s1",
        new RoleTodoEscalationRequest("凭证和金额不一致，需要老板最终判断", "RISK")
    );
    RoleTodoEscalationResponse warehouseEscalation = service.escalate(
        warehouse(),
        RoleTodoAudience.WAREHOUSE,
        "warehouse-req-s1",
        new RoleTodoEscalationRequest("门店急需，库存不足，需要老板确认优先级", "PENDING")
    );

    BossTodoDashboardResponse dashboard =
        service.bossDashboard(boss(), new RoleTodoQuery(false, null, 200, null, null));

    assertThat(dashboard.roleName()).isEqualTo("老板（系统管理员）");
    assertThat(dashboard.todayFocus().needsBossActionCount()).isEqualTo(2);
    assertThat(dashboard.todayFocus().roleWorkCount()).isEqualTo(5);
    assertThat(dashboard.needsBossAction()).extracting(RoleTodoItemResponse::id)
        .containsExactlyInAnyOrder(financeEscalation.bossTodoId(), warehouseEscalation.bossTodoId());
    assertThat(dashboard.needsBossAction())
        .allSatisfy(item -> assertThat(item.id()).startsWith("boss-escalation-"));
    assertThat(dashboard.highRiskReminders()).extracting(BossTodoRiskGroupResponse::sourceModule)
        .contains("利润表", "督导巡店");
    assertThat(dashboard.highRiskReminders()).filteredOn(group -> "利润表".equals(group.sourceModule()))
        .hasSize(2)
        .allSatisfy(group -> {
          assertThat(group.ownerName()).isEqualTo("财务");
          assertThat(group.highestRisk()).contains("风险");
          assertThat(group.topStores()).isNotEmpty();
        });
    assertThat(dashboard.roleProgress()).extracting(BossTodoOwnerGroupResponse::ownerName)
        .contains("财务", "督导", "仓库管理员");
    assertThat(dashboard.roleProgress()).filteredOn(group -> "财务".equals(group.ownerName()))
        .singleElement()
        .satisfies(group -> {
          assertThat(group.openCount()).isEqualTo(3);
          assertThat(group.riskCount()).isEqualTo(2);
          assertThat(group.pendingCount()).isEqualTo(1);
        });
  }

  @Test
  void bossDashboardDoneReviewContainsClosedEscalationAfterNoImpactClose() {
    RoleTodoEscalationResponse escalation = service.escalate(
        storeManager(),
        RoleTodoAudience.STORE_MANAGER,
        "inspection-insp-s1",
        new RoleTodoEscalationRequest("需要老板判断是否继续整改", "RISK")
    );

    service.close(boss(), escalation.bossTodoId(), new RoleTodoCompletionRequest(" ", List.of()));

    BossTodoDashboardResponse dashboard =
        service.bossDashboard(boss(), new RoleTodoQuery(true, "DONE", 50, null, null));

    assertThat(dashboard.needsBossAction()).extracting(RoleTodoItemResponse::id)
        .doesNotContain(escalation.bossTodoId());
    assertThat(dashboard.doneReview()).extracting(RoleTodoItemResponse::id)
        .contains(escalation.bossTodoId());
    assertThat(dashboard.doneReview()).filteredOn(item -> escalation.bossTodoId().equals(item.id()))
        .singleElement()
        .satisfies(item -> assertThat(item.processStatus()).isEqualTo("事情没有很大影响，已默认处理"));
  }

  @Test
  void supervisorAndWarehouseReceiveRoleSpecificTodos() {
    RoleTodoResponse supervisor = service.todos(supervisor(), RoleTodoAudience.SUPERVISOR, new RoleTodoQuery(false, null, 50, null, null));
    RoleTodoResponse warehouse = service.todos(warehouse(), RoleTodoAudience.WAREHOUSE, new RoleTodoQuery(false, null, 50, null, null));

    assertThat(supervisor.items()).extracting(RoleTodoItemResponse::sourceModule).containsExactly("\u7763\u5bfc\u5de1\u5e97");
    assertThat(warehouse.items()).extracting(RoleTodoItemResponse::sourceModule).containsExactly("\u4ed3\u5e93\u53eb\u8d27");
  }

  @Test
  void roleTodosReflectOpenEscalationState() {
    RoleTodoItemResponse supervisorBefore = service.todos(supervisor(), RoleTodoAudience.SUPERVISOR, new RoleTodoQuery(false, null, 50, null, null))
        .items()
        .getFirst();
    RoleTodoItemResponse warehouseBefore = service.todos(warehouse(), RoleTodoAudience.WAREHOUSE, new RoleTodoQuery(false, null, 50, null, null))
        .items()
        .getFirst();

    assertThat(supervisorBefore.escalatedToBoss()).isFalse();
    assertThat(warehouseBefore.escalatedToBoss()).isFalse();

    service.escalate(
        supervisor(),
        RoleTodoAudience.SUPERVISOR,
        supervisorBefore.id(),
        new RoleTodoEscalationRequest("\u5df2\u8ddf\u8fdb\u591a\u6b21\uff0c\u9700\u8981\u8001\u677f\u534f\u8c03", "RISK")
    );
    service.escalate(
        warehouse(),
        RoleTodoAudience.WAREHOUSE,
        warehouseBefore.id(),
        new RoleTodoEscalationRequest("\u95e8\u5e97\u6025\u8981\uff0c\u9700\u8981\u8001\u677f\u534f\u8c03", "PENDING")
    );

    RoleTodoItemResponse supervisorAfter = service.todos(supervisor(), RoleTodoAudience.SUPERVISOR, new RoleTodoQuery(false, null, 50, null, null))
        .items()
        .getFirst();
    RoleTodoItemResponse warehouseAfter = service.todos(warehouse(), RoleTodoAudience.WAREHOUSE, new RoleTodoQuery(false, null, 50, null, null))
        .items()
        .getFirst();

    assertThat(supervisorAfter.escalatedToBoss()).isTrue();
    assertThat(warehouseAfter.escalatedToBoss()).isTrue();
  }

  @Test
  void storeManagerTodosAreLockedToOwnStoreAndFinanceEndpointUsesRealData() {
    RoleTodoResponse manager = service.todos(storeManager(), RoleTodoAudience.STORE_MANAGER, new RoleTodoQuery(false, null, 50, null, "s2"));
    RoleTodoResponse finance = service.todos(finance(), RoleTodoAudience.FINANCE, new RoleTodoQuery(false, null, 50, null, null));

    assertThat(manager.items()).extracting(RoleTodoItemResponse::storeId).containsOnly("s1");
    assertThat(finance.roleName()).isEqualTo("\u8d22\u52a1");
    assertThat(finance.items()).extracting(RoleTodoItemResponse::id)
        .containsExactly("profit-risk-s1-2026-07", "expense-exp-s1");
    assertThat(finance.items()).extracting(RoleTodoItemResponse::sourceModule)
        .containsExactly("\u5229\u6da6\u8868", "\u62a5\u9500");
    assertThat(finance.stats()).hasSize(4);
    assertThat(finance.stats()).filteredOn(stat -> "RISK".equals(stat.status()))
        .singleElement()
        .satisfies(stat -> assertThat(stat.count()).isEqualTo(1));
    assertThat(finance.stats()).filteredOn(stat -> "PENDING".equals(stat.status()))
        .singleElement()
        .satisfies(stat -> assertThat(stat.count()).isEqualTo(1));
  }

  @Test
  void endpointRoleIsEnforcedExceptBossCanPreview() {
    assertThat(service.todos(boss(), RoleTodoAudience.FINANCE, new RoleTodoQuery(false, null, 50, null, null)).roleName())
        .isEqualTo("\u8d22\u52a1");
    assertThatThrownBy(() -> service.todos(finance(), RoleTodoAudience.WAREHOUSE, new RoleTodoQuery(false, null, 50, null, null)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void financeCanEscalateTodoWithReasonAndBossCanSeeIt() {
    RoleTodoEscalationResponse escalation = service.escalate(
        finance(),
        RoleTodoAudience.FINANCE,
        "expense-exp-s1",
        new RoleTodoEscalationRequest("\u51ed\u8bc1\u7f3a\u5931\uff0c\u95e8\u5e97\u65e0\u6cd5\u8865\u9f50", "RISK")
    );

    assertThat(escalation.escalationId()).isNotBlank();
    assertThat(escalation.bossTodoId()).isEqualTo("boss-escalation-" + escalation.escalationId());

    RoleTodoResponse bossTodos = service.todos(boss(), RoleTodoAudience.BOSS, new RoleTodoQuery(false, null, 50, null, null));

    assertThat(bossTodos.items()).extracting(RoleTodoItemResponse::id)
        .contains(escalation.bossTodoId());
    RoleTodoItemResponse bossItem = bossTodos.items().stream()
        .filter(item -> escalation.bossTodoId().equals(item.id()))
        .findFirst()
        .orElseThrow();
    assertThat(bossItem.status()).isEqualTo("RISK");
    assertThat(bossItem.sourceModule()).isEqualTo("\u8d22\u52a1\u4e0a\u62a5");
    assertThat(bossItem.sourceRecordId()).isEqualTo("expense-exp-s1");
    assertThat(bossItem.escalatedToBoss()).isTrue();
    assertThat(bossItem.summary()).contains("\u51ed\u8bc1\u7f3a\u5931");
  }

  @Test
  void escalationRequiresReasonAndSourceRole() {
    assertThatThrownBy(() -> service.escalate(
        finance(),
        RoleTodoAudience.FINANCE,
        "expense-claim-1",
        new RoleTodoEscalationRequest("   ", "PENDING")
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("BAD_REASON"));

    assertThatThrownBy(() -> service.escalate(
        finance(),
        RoleTodoAudience.WAREHOUSE,
        "warehouse-req-1",
        new RoleTodoEscalationRequest("cannot process", "PENDING")
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void roleCanResolveTodoWithRequiredNoteAndMysqlAttachment() {
    String attachmentBase64 = Base64.getEncoder()
        .encodeToString("receipt checked".getBytes(StandardCharsets.UTF_8));

    RoleTodoActionResultResponse result = service.resolve(
        finance(),
        RoleTodoAudience.FINANCE,
        "expense-exp-s1",
        new RoleTodoCompletionRequest(
            "已经核对凭证和金额，允许进入后续流程",
            List.of(new RoleTodoAttachmentRequest("receipt.txt", "text/plain", attachmentBase64))
        )
    );

    assertThat(result.status()).isEqualTo("DONE");
    assertThat(result.attachmentCount()).isEqualTo(1);
    assertThat(service.todos(finance(), RoleTodoAudience.FINANCE, new RoleTodoQuery(false, null, 50, null, null))
        .items())
        .extracting(RoleTodoItemResponse::id)
        .doesNotContain("expense-exp-s1");
    RoleTodoItemResponse doneItem = service.todos(finance(), RoleTodoAudience.FINANCE, new RoleTodoQuery(true, "DONE", 50, null, null))
        .items()
        .stream()
        .filter(item -> "expense-exp-s1".equals(item.id()))
        .findFirst()
        .orElseThrow();
    assertThat(doneItem.status()).isEqualTo("DONE");
    assertThat(doneItem.processStatus()).contains("已经核对凭证");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from todo_action_attachment where todo_id = 'expense-exp-s1'",
        Integer.class
    )).isEqualTo(1);
    byte[] stored = jdbcTemplate.queryForObject(
        "select content from todo_action_attachment where todo_id = 'expense-exp-s1'",
        byte[].class
    );
    assertThat(new String(stored, StandardCharsets.UTF_8)).isEqualTo("receipt checked");
  }

  @Test
  void roleResolveWritesOperationLogForAuditReview() {
    service.resolve(
        finance(),
        RoleTodoAudience.FINANCE,
        "expense-exp-s1",
        new RoleTodoCompletionRequest("财务已核对，事项完成", List.of())
    );

    assertThat(jdbcTemplate.queryForObject(
        """
        select count(*) from operation_log
        where tenant_id = 1
          and operator_id = 2
          and operator_name = 'FINANCE'
          and action = 'todo_resolve'
          and target_type = 'role_todo'
          and target_id = 'expense-exp-s1'
          and store_id = 's1'
          and month = '2026-07'
          and reason = '财务已核对，事项完成'
        """,
        Integer.class
    )).isEqualTo(1);
  }

  @Test
  void statsCountDoneTodosEvenWhenDefaultListHidesDoneItems() {
    service.resolve(
        finance(),
        RoleTodoAudience.FINANCE,
        "expense-exp-s1",
        new RoleTodoCompletionRequest("已处理，默认列表不再展示", List.of())
    );

    RoleTodoResponse response = service.todos(finance(), RoleTodoAudience.FINANCE, new RoleTodoQuery(false, null, 50, null, null));

    assertThat(response.items()).extracting(RoleTodoItemResponse::id)
        .doesNotContain("expense-exp-s1");
    assertThat(response.stats()).filteredOn(stat -> "DONE".equals(stat.status()))
        .singleElement()
        .satisfies(stat -> assertThat(stat.count()).isEqualTo(1));
  }

  @Test
  void resolvingNonInspectionTodoDoesNotChangeInspectionRawOutcome() {
    service.resolve(
        finance(),
        RoleTodoAudience.FINANCE,
        "expense-exp-s1",
        new RoleTodoCompletionRequest("财务已核对金额和凭证，报销事项完成", List.of())
    );
    assertThat(jdbcTemplate.queryForObject(
        "select status from expense_claim where id = 'exp-s1'",
        String.class
    )).isEqualTo("已完成");
    assertThat(jdbcTemplate.queryForObject(
        "select passed from inspection_record where id = 'insp-s1'",
        Integer.class
    )).isEqualTo(0);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from todo_action where todo_id = 'inspection-insp-s1' and status = 'DONE'",
        Integer.class
    )).isZero();
  }

  @Test
  void warehouseTodoCannotBeManuallyResolvedBeforeSourceBusinessIsDone() {
    assertThatThrownBy(() -> service.resolve(
        warehouse(),
        RoleTodoAudience.WAREHOUSE,
        "warehouse-req-s1",
        new RoleTodoCompletionRequest("仓库待办不能绕过发货流程", List.of())
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("WAREHOUSE_TODO_NOT_READY"));

    assertThat(jdbcTemplate.queryForObject(
        "select status from store_requisition where id = 'req-s1'",
        String.class
    )).isEqualTo("SUBMITTED");
  }

  @Test
  void lowStockTodoCannotBeManuallyResolvedWhileStockIsStillLow() {
    jdbcTemplate.update("""
        insert into warehouse_item(id, tenant_id, name, unit, min_stock_quantity, alert_enabled, expiry_alert_days, active)
        values (1, 1, '鲜奶', '件', 40.00, 1, 3, 1)
        """);
    jdbcTemplate.update("""
        insert into warehouse_stock_batch(tenant_id, item_id, quantity, expiry_date)
        values (1, 1, 30.00, null)
        """);

    assertThatThrownBy(() -> service.resolve(
        warehouse(),
        RoleTodoAudience.WAREHOUSE,
        "warehouse-alert-low-1",
        new RoleTodoCompletionRequest("库存预警不能直接关闭", List.of())
    ))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo("LOW_STOCK_TODO_NOT_READY"));
  }

  @Test
  void storeManagerCanEscalateAndBossCanCloseWithDefaultNoImpactNote() {
    RoleTodoEscalationResponse escalation = service.escalate(
        storeManager(),
        RoleTodoAudience.STORE_MANAGER,
        "inspection-insp-s1",
        new RoleTodoEscalationRequest("整改需要老板判断是否暂停营业", "RISK")
    );

    RoleTodoItemResponse bossItem = service.todos(boss(), RoleTodoAudience.BOSS, new RoleTodoQuery(false, null, 50, null, null))
        .items()
        .stream()
        .filter(item -> escalation.bossTodoId().equals(item.id()))
        .findFirst()
        .orElseThrow();
    assertThat(bossItem.summary()).contains("整改需要老板判断");

    RoleTodoActionResultResponse closeResult = service.close(
        boss(),
        escalation.bossTodoId(),
        new RoleTodoCompletionRequest(" ", List.of())
    );

    assertThat(closeResult.status()).isEqualTo("DONE");
    assertThat(closeResult.processStatus()).isEqualTo("事情没有很大影响，已默认处理");
    assertThat(jdbcTemplate.queryForObject(
        "select status from todo_escalation where boss_todo_id = ?",
        String.class,
        escalation.bossTodoId()
    )).isEqualTo("RESOLVED");
    assertThat(service.todos(storeManager(), RoleTodoAudience.STORE_MANAGER, new RoleTodoQuery(false, null, 50, null, null))
        .items())
        .extracting(RoleTodoItemResponse::id)
        .doesNotContain("inspection-insp-s1");

    RoleTodoResponse bossAfterClose = service.todos(boss(), RoleTodoAudience.BOSS, new RoleTodoQuery(false, null, 50, null, null));
    assertThat(bossAfterClose.items()).extracting(RoleTodoItemResponse::id)
        .doesNotContain(escalation.bossTodoId());
    assertThat(bossAfterClose.stats()).filteredOn(stat -> "DONE".equals(stat.status()))
        .singleElement()
        .satisfies(stat -> assertThat(stat.count()).isEqualTo(1));
  }

  @Test
  void bossCloseWritesOperationLogsForBossTodoAndSourceTodo() {
    RoleTodoEscalationResponse escalation = service.escalate(
        warehouse(),
        RoleTodoAudience.WAREHOUSE,
        "warehouse-req-s1",
        new RoleTodoEscalationRequest("门店急需，仓库无法判断是否优先", "PENDING")
    );

    service.close(
        boss(),
        escalation.bossTodoId(),
        new RoleTodoCompletionRequest(" ", List.of())
    );

    assertThat(jdbcTemplate.queryForObject(
        """
        select count(*) from operation_log
        where tenant_id = 1
          and operator_id = 1
          and action = 'todo_close'
          and target_type = 'role_todo'
          and target_id = ?
          and reason = '事情没有很大影响，已默认处理'
        """,
        Integer.class,
        escalation.bossTodoId()
    )).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        """
        select count(*) from operation_log
        where tenant_id = 1
          and operator_id = 1
          and action = 'todo_close_source'
          and target_type = 'role_todo'
          and target_id = 'warehouse-req-s1'
          and reason = '事情没有很大影响，已默认处理'
        """,
        Integer.class
    )).isEqualTo(1);
  }

  @Test
  void bossSeesDataImportIssuesFromMigrationErrorKeys() {
    jdbcTemplate.update("""
        insert into kv_storage(storage_key, storage_value, updated_at)
        values ('migration_error:expenses', '{"message":"bad import"}', current_timestamp)
        """);

    RoleTodoResponse response = service.todos(boss(), RoleTodoAudience.BOSS, new RoleTodoQuery(false, null, 50, null, null));

    assertThat(response.items()).extracting(RoleTodoItemResponse::sourceModule)
        .contains("数据导入异常");
  }

  @Test
  void overduePendingTodosAreClearlyMarkedAsOverdue() {
    jdbcTemplate.update("""
        insert into expense_claim(id, tenant_id, store_id, month, amount, category, reason, status, created_at)
        values ('exp-overdue', 1, 's1', '2026-07', 99.00, 'repair', 'late receipt', '待审核', '2026-07-01 08:00:00')
        """);

    RoleTodoResponse response = service.todos(finance(), RoleTodoAudience.FINANCE, new RoleTodoQuery(false, "RISK", 50, null, null));

    RoleTodoItemResponse item = response.items().stream()
        .filter(todo -> "expense-exp-overdue".equals(todo.id()))
        .findFirst()
        .orElseThrow();
    assertThat(item.sourceModule()).isEqualTo("逾期未处理事项");
    assertThat(item.processStatus()).contains("已逾期");
  }

  private AuthUser boss() {
    return user(1L, "BOSS", null);
  }

  private AuthUser finance() {
    return user(2L, "FINANCE", null);
  }

  private AuthUser supervisor() {
    return user(3L, "SUPERVISOR", null);
  }

  private AuthUser warehouse() {
    return user(4L, "WAREHOUSE", null);
  }

  private AuthUser storeManager() {
    return user(5L, "STORE_MANAGER", "s1");
  }

  private AuthUser user(long id, String role, String storeId) {
    return new AuthUser(id, 1L, "default", role.toLowerCase(), "", role, role, storeId, true);
  }
}

-- ============================================================
-- V49: Inspection Score Scale Governance
-- ============================================================
-- 1. Add test_marker column so E2E/test records never appear in
--    official lists, statistics, or exports.
-- 2. Create the score-scale migration audit table so the backend
--    can record safe 100→200 conversions and block repeated runs.
-- 3. Mark the existing E2E seed record as test data.

alter table inspection_record
  add column test_marker varchar(32) default null
  after result_code;

create table if not exists inspection_score_scale_migration_audit (
  id                        bigint auto_increment primary key,
  tenant_id                 bigint        not null default 1,
  inspection_record_id      varchar(120)  not null,
  migration_key             varchar(64)   not null,
  original_full_score       decimal(8,2),
  original_pass_score       decimal(8,2),
  original_score            decimal(8,2)  not null,
  original_material_score   decimal(8,2),
  original_hygiene_score    decimal(8,2),
  original_service_score    decimal(8,2),
  original_passed           tinyint(1),
  original_result_code      varchar(32),
  converted_full_score      decimal(8,2)  not null,
  converted_pass_score      decimal(8,2),
  converted_score           decimal(8,2)  not null,
  converted_material_score  decimal(8,2),
  converted_hygiene_score   decimal(8,2),
  converted_service_score   decimal(8,2),
  converted_passed          tinyint(1),
  converted_result_code     varchar(32),
  migrated_at               timestamp     not null default current_timestamp,
  constraint fk_inspection_score_scale_audit_record
    foreign key (inspection_record_id) references inspection_record(id)
) engine=InnoDB default charset=utf8mb4;

-- Mark the existing E2E seed record so it never appears in
-- official inspection lists, statistics or exports.
update inspection_record
  set test_marker = 'E2E-SEED'
where id = 'e2e-seed-redline-inspection'
  and test_marker is null;

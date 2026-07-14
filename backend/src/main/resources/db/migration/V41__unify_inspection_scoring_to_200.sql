-- V41: Make 200/180 the only persisted inspection score scale.
-- Historical 100-point rows are backed up before conversion. Rows already on
-- the 200-point scale are deliberately excluded, making the conversion one-shot.

drop procedure if exists v41_assert_inspection_scoring_preconditions;

delimiter $$

create procedure v41_assert_inspection_scoring_preconditions()
begin
  if exists (
    select 1
    from inspection_record
    where coalesce(full_score, 100.00) not in (100.00, 200.00)
       or coalesce(score, 0.00) < 0.00
       or coalesce(score, 0.00) > coalesce(full_score, 100.00)
  ) then
    signal sqlstate '45000'
      set message_text = 'V41 found an unsupported inspection score scale';
  end if;

  -- Partial category columns and issue-only snapshots were valid in the legacy
  -- application. They do not have to add up to the record score. Only mutually
  -- contradictory standard-scale evidence is unsafe to convert automatically.
  if exists (
    select 1
    from inspection_record record
    where record.full_score = 100.00
      and (
        exists (
          select 1
          from inspection_standard_version version
          where version.id = record.standard_version_id
            and version.full_score not in (100.00, 200.00)
        )
        or exists (
          select 1
          from inspection_record_standard_snapshot snapshot
          join inspection_standard_item item on item.id = snapshot.standard_id
          join inspection_standard_version version on version.id = item.standard_version_id
          where snapshot.tenant_id = record.tenant_id
            and snapshot.inspection_record_id = record.id
            and version.full_score not in (100.00, 200.00)
        )
        or (
          (
            exists (
              select 1 from inspection_standard_version version
              where version.id = record.standard_version_id and version.full_score = 100.00
            )
            or exists (
              select 1
              from inspection_record_standard_snapshot snapshot
              join inspection_standard_item item on item.id = snapshot.standard_id
              join inspection_standard_version version on version.id = item.standard_version_id
              where snapshot.tenant_id = record.tenant_id
                and snapshot.inspection_record_id = record.id
                and version.full_score = 100.00
            )
          )
          and (
            exists (
              select 1 from inspection_standard_version version
              where version.id = record.standard_version_id and version.full_score = 200.00
            )
            or exists (
              select 1
              from inspection_record_standard_snapshot snapshot
              join inspection_standard_item item on item.id = snapshot.standard_id
              join inspection_standard_version version on version.id = item.standard_version_id
              where snapshot.tenant_id = record.tenant_id
                and snapshot.inspection_record_id = record.id
                and version.full_score = 200.00
            )
          )
        )
      )
  ) then
    signal sqlstate '45000'
      set message_text = 'V41 found conflicting inspection snapshot scale evidence';
  end if;

  -- Validate totals only for a demonstrably complete snapshot: every enabled
  -- item from the linked standard version appears exactly once. Legacy
  -- deduction-only snapshots deliberately bypass this aggregate check.
  if exists (
    select 1
    from inspection_record record
    join inspection_standard_version version
      on version.id = record.standard_version_id
    join inspection_record_standard_snapshot snapshot
      on snapshot.tenant_id = record.tenant_id
     and snapshot.inspection_record_id = record.id
    join inspection_standard_item item
      on item.id = snapshot.standard_id
     and item.standard_version_id = version.id
     and item.enabled = 1
    where record.full_score = 100.00
      and version.full_score in (100.00, 200.00)
    group by record.tenant_id, record.id, record.standard_version_id, version.full_score
    having count(*) = (
          select count(*)
          from inspection_record_standard_snapshot all_snapshot
          where all_snapshot.tenant_id = record.tenant_id
            and all_snapshot.inspection_record_id = record.id
        )
       and count(distinct snapshot.standard_id) = count(*)
       and count(*) = (
          select count(*)
          from inspection_standard_item expected
          where expected.standard_version_id = record.standard_version_id
            and expected.enabled = 1
        )
       and (
         abs(
           case when max(version.full_score) = 100.00
             then round(sum(snapshot.suggested_score) * 2, 2)
             else sum(snapshot.suggested_score)
           end - 200.00
         ) > 0.01
         or abs(
           case when max(version.full_score) = 100.00
             then round(sum(snapshot.actual_score) * 2, 2)
             else sum(snapshot.actual_score)
           end - round(max(record.score) * 2, 2)
         ) > 0.01
       )
  ) then
    signal sqlstate '45000'
      set message_text = 'V41 found inconsistent complete inspection snapshot scores';
  end if;

  if exists (
    select 1
    from inspection_standard_version version
    left join inspection_standard_item item
      on item.standard_version_id = version.id
     and item.enabled = 1
    where version.status = 'ACTIVE'
    group by version.id
    having max(version.full_score) <> 200.00
       or max(version.pass_score) <> 180.00
       or coalesce(sum(item.suggested_score), 0.00) <> 200.00
  ) then
    signal sqlstate '45000'
      set message_text = 'V41 active inspection standard must total 200 with pass score 180';
  end if;
end$$

delimiter ;

call v41_assert_inspection_scoring_preconditions();
drop procedure v41_assert_inspection_scoring_preconditions;

alter table inspection_record
  add column pass_score decimal(8,2) not null default 180.00 after full_score;

create table inspection_score_scale_migration_audit (
  id bigint not null auto_increment primary key,
  tenant_id bigint not null,
  inspection_record_id varchar(120) not null,
  migration_key varchar(64) not null,
  original_full_score decimal(8,2) null,
  original_pass_score decimal(8,2) null,
  original_score decimal(8,2) null,
  original_material_score decimal(8,2) null,
  original_hygiene_score decimal(8,2) null,
  original_service_score decimal(8,2) null,
  original_passed tinyint(1) not null,
  original_result_code varchar(32) null,
  converted_full_score decimal(8,2) not null,
  converted_pass_score decimal(8,2) not null,
  converted_score decimal(8,2) not null,
  converted_material_score decimal(8,2) null,
  converted_hygiene_score decimal(8,2) null,
  converted_service_score decimal(8,2) null,
  converted_passed tinyint(1) not null,
  converted_result_code varchar(32) not null,
  migrated_at timestamp not null default current_timestamp,
  constraint uk_inspection_score_scale_migration
    unique (tenant_id, inspection_record_id, migration_key),
  index idx_inspection_score_scale_migrated_at (tenant_id, migrated_at),
  constraint fk_inspection_score_scale_tenant
    foreign key (tenant_id) references tenant(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into inspection_score_scale_migration_audit(
  tenant_id, inspection_record_id, migration_key,
  original_full_score, original_pass_score, original_score,
  original_material_score, original_hygiene_score, original_service_score,
  original_passed, original_result_code,
  converted_full_score, converted_pass_score, converted_score,
  converted_material_score, converted_hygiene_score, converted_service_score,
  converted_passed, converted_result_code
)
select record.tenant_id,
       record.id,
       'V41_100_TO_200',
       record.full_score,
       (
         select version.pass_score
         from inspection_standard_version version
         where version.id = record.standard_version_id
       ),
       record.score,
       record.material_score,
       record.hygiene_score,
       record.service_score,
       record.passed,
       record.result_code,
       200.00,
       180.00,
       least(200.00, greatest(0.00, round(coalesce(record.score, 0.00) * 2, 2))),
       case
         when record.material_score is null then null
         when abs(
           coalesce(record.material_score, 0.00)
             + coalesce(record.hygiene_score, 0.00)
             + coalesce(record.service_score, 0.00)
             - round(coalesce(record.score, 0.00) * 2, 2)
         ) <= 0.01
           and (
             exists (
               select 1 from inspection_standard_version version
               where version.id = record.standard_version_id and version.full_score = 200.00
             )
             or exists (
               select 1
               from inspection_record_standard_snapshot snapshot
               join inspection_standard_item item on item.id = snapshot.standard_id
               join inspection_standard_version version on version.id = item.standard_version_id
               where snapshot.tenant_id = record.tenant_id
                 and snapshot.inspection_record_id = record.id
                 and version.full_score = 200.00
             )
           ) then record.material_score
         else round(record.material_score * 2, 2)
       end,
       case
         when record.hygiene_score is null then null
         when abs(
           coalesce(record.material_score, 0.00)
             + coalesce(record.hygiene_score, 0.00)
             + coalesce(record.service_score, 0.00)
             - round(coalesce(record.score, 0.00) * 2, 2)
         ) <= 0.01
           and (
             exists (
               select 1 from inspection_standard_version version
               where version.id = record.standard_version_id and version.full_score = 200.00
             )
             or exists (
               select 1
               from inspection_record_standard_snapshot snapshot
               join inspection_standard_item item on item.id = snapshot.standard_id
               join inspection_standard_version version on version.id = item.standard_version_id
               where snapshot.tenant_id = record.tenant_id
                 and snapshot.inspection_record_id = record.id
                 and version.full_score = 200.00
             )
           ) then record.hygiene_score
         else round(record.hygiene_score * 2, 2)
       end,
       case
         when record.service_score is null then null
         when abs(
           coalesce(record.material_score, 0.00)
             + coalesce(record.hygiene_score, 0.00)
             + coalesce(record.service_score, 0.00)
             - round(coalesce(record.score, 0.00) * 2, 2)
         ) <= 0.01
           and (
             exists (
               select 1 from inspection_standard_version version
               where version.id = record.standard_version_id and version.full_score = 200.00
             )
             or exists (
               select 1
               from inspection_record_standard_snapshot snapshot
               join inspection_standard_item item on item.id = snapshot.standard_id
               join inspection_standard_version version on version.id = item.standard_version_id
               where snapshot.tenant_id = record.tenant_id
                 and snapshot.inspection_record_id = record.id
                 and version.full_score = 200.00
             )
           ) then record.service_score
         else round(record.service_score * 2, 2)
       end,
       case
         when upper(coalesce(record.result_code, '')) = 'RED_LINE_FAILED'
           or lower(trim(coalesce(record.redlines_json, ''))) not in ('', '[]', 'null') then 0
         when least(200.00, greatest(0.00, round(coalesce(record.score, 0.00) * 2, 2))) >= 180.00 then 1
         else 0
       end,
       case
         when upper(coalesce(record.result_code, '')) = 'RED_LINE_FAILED'
           or lower(trim(coalesce(record.redlines_json, ''))) not in ('', '[]', 'null') then 'RED_LINE_FAILED'
         when least(200.00, greatest(0.00, round(coalesce(record.score, 0.00) * 2, 2))) >= 180.00 then 'PASSED'
         else 'FAILED'
       end
from inspection_record record
where (
    record.full_score = 100.00
    or (
      record.full_score is null
      and record.standard_version_id is null
      and not exists (
        select 1
        from inspection_record_standard_snapshot evidence
        where evidence.tenant_id = record.tenant_id
          and evidence.inspection_record_id = record.id
          and evidence.suggested_score > 100.00
      )
    )
  )
  and not exists (
    select 1
    from inspection_score_scale_migration_audit audit
    where audit.tenant_id = record.tenant_id
      and audit.inspection_record_id = record.id
      and audit.migration_key = 'V41_100_TO_200'
  );

-- Preserve and correct stale result flags on records that are already 200-point.
-- Their scores are copied unchanged and are never multiplied.
insert into inspection_score_scale_migration_audit(
  tenant_id, inspection_record_id, migration_key,
  original_full_score, original_pass_score, original_score,
  original_material_score, original_hygiene_score, original_service_score,
  original_passed, original_result_code,
  converted_full_score, converted_pass_score, converted_score,
  converted_material_score, converted_hygiene_score, converted_service_score,
  converted_passed, converted_result_code
)
select record.tenant_id,
       record.id,
       'V41_RESULT_RECALC_200',
       record.full_score,
       (
         select version.pass_score
         from inspection_standard_version version
         where version.id = record.standard_version_id
       ),
       record.score,
       record.material_score,
       record.hygiene_score,
       record.service_score,
       record.passed,
       record.result_code,
       200.00,
       180.00,
       record.score,
       record.material_score,
       record.hygiene_score,
       record.service_score,
       case
         when upper(coalesce(record.result_code, '')) = 'RED_LINE_FAILED'
           or lower(trim(coalesce(record.redlines_json, ''))) not in ('', '[]', 'null') then 0
         when record.score >= 180.00 then 1
         else 0
       end,
       case
         when upper(coalesce(record.result_code, '')) = 'RED_LINE_FAILED'
           or lower(trim(coalesce(record.redlines_json, ''))) not in ('', '[]', 'null') then 'RED_LINE_FAILED'
         when record.score >= 180.00 then 'PASSED'
         else 'FAILED'
       end
from inspection_record record
where record.full_score = 200.00
  and (
    record.passed <> case
      when upper(coalesce(record.result_code, '')) = 'RED_LINE_FAILED'
        or lower(trim(coalesce(record.redlines_json, ''))) not in ('', '[]', 'null') then 0
      when record.score >= 180.00 then 1
      else 0
    end
    or upper(coalesce(record.result_code, '')) <> case
      when upper(coalesce(record.result_code, '')) = 'RED_LINE_FAILED'
        or lower(trim(coalesce(record.redlines_json, ''))) not in ('', '[]', 'null') then 'RED_LINE_FAILED'
      when record.score >= 180.00 then 'PASSED'
      else 'FAILED'
    end
  )
  and not exists (
    select 1
    from inspection_score_scale_migration_audit audit
    where audit.tenant_id = record.tenant_id
      and audit.inspection_record_id = record.id
      and audit.migration_key = 'V41_RESULT_RECALC_200'
  );

create table inspection_score_scale_item_migration_audit (
  id bigint not null auto_increment primary key,
  score_migration_audit_id bigint not null,
  inspection_snapshot_id bigint not null,
  original_suggested_score decimal(8,2) not null,
  original_deduction_score decimal(8,2) not null,
  original_actual_score decimal(8,2) not null,
  converted_suggested_score decimal(8,2) not null,
  converted_deduction_score decimal(8,2) not null,
  converted_actual_score decimal(8,2) not null,
  migrated_at timestamp not null default current_timestamp,
  constraint uk_inspection_score_scale_item_migration
    unique (score_migration_audit_id, inspection_snapshot_id),
  constraint fk_inspection_score_scale_item_audit
    foreign key (score_migration_audit_id)
      references inspection_score_scale_migration_audit(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

insert into inspection_score_scale_item_migration_audit(
  score_migration_audit_id, inspection_snapshot_id,
  original_suggested_score, original_deduction_score, original_actual_score,
  converted_suggested_score, converted_deduction_score, converted_actual_score
)
select audit.id,
       snapshot.id,
       snapshot.suggested_score,
       snapshot.actual_deduction_score,
       snapshot.actual_score,
       case when (
         exists (
           select 1 from inspection_standard_version version
           where version.id = record.standard_version_id and version.full_score = 200.00
         )
         or exists (
           select 1
           from inspection_record_standard_snapshot evidence
           join inspection_standard_item item on item.id = evidence.standard_id
           join inspection_standard_version version on version.id = item.standard_version_id
           where evidence.tenant_id = record.tenant_id
             and evidence.inspection_record_id = record.id
             and version.full_score = 200.00
         )
       ) then snapshot.suggested_score else round(snapshot.suggested_score * 2, 2) end,
       case when (
         exists (
           select 1 from inspection_standard_version version
           where version.id = record.standard_version_id and version.full_score = 200.00
         )
         or exists (
           select 1
           from inspection_record_standard_snapshot evidence
           join inspection_standard_item item on item.id = evidence.standard_id
           join inspection_standard_version version on version.id = item.standard_version_id
           where evidence.tenant_id = record.tenant_id
             and evidence.inspection_record_id = record.id
             and version.full_score = 200.00
         )
       ) then snapshot.actual_deduction_score else round(snapshot.actual_deduction_score * 2, 2) end,
       case when (
         exists (
           select 1 from inspection_standard_version version
           where version.id = record.standard_version_id and version.full_score = 200.00
         )
         or exists (
           select 1
           from inspection_record_standard_snapshot evidence
           join inspection_standard_item item on item.id = evidence.standard_id
           join inspection_standard_version version on version.id = item.standard_version_id
           where evidence.tenant_id = record.tenant_id
             and evidence.inspection_record_id = record.id
             and version.full_score = 200.00
         )
       ) then snapshot.actual_score else round(snapshot.actual_score * 2, 2) end
from inspection_score_scale_migration_audit audit
join inspection_record record
  on record.tenant_id = audit.tenant_id
 and record.id = audit.inspection_record_id
join inspection_record_standard_snapshot snapshot
  on snapshot.tenant_id = audit.tenant_id
 and snapshot.inspection_record_id = audit.inspection_record_id
where audit.migration_key = 'V41_100_TO_200'
  and not exists (
    select 1
    from inspection_score_scale_item_migration_audit existing
    where existing.score_migration_audit_id = audit.id
      and existing.inspection_snapshot_id = snapshot.id
  );

update inspection_record_standard_snapshot snapshot
set suggested_score = (
      select audit.converted_suggested_score
      from inspection_score_scale_item_migration_audit audit
      where audit.inspection_snapshot_id = snapshot.id
    ),
    actual_deduction_score = (
      select audit.converted_deduction_score
      from inspection_score_scale_item_migration_audit audit
      where audit.inspection_snapshot_id = snapshot.id
    ),
    actual_score = (
      select audit.converted_actual_score
      from inspection_score_scale_item_migration_audit audit
      where audit.inspection_snapshot_id = snapshot.id
    )
where exists (
  select 1
  from inspection_score_scale_item_migration_audit audit
  where audit.inspection_snapshot_id = snapshot.id
);

update inspection_record record
set full_score = 200.00,
    pass_score = 180.00,
    score = (
      select audit.converted_score
      from inspection_score_scale_migration_audit audit
      where audit.tenant_id = record.tenant_id
        and audit.inspection_record_id = record.id
        and audit.migration_key = 'V41_100_TO_200'
    ),
    material_score = (
      select audit.converted_material_score
      from inspection_score_scale_migration_audit audit
      where audit.tenant_id = record.tenant_id
        and audit.inspection_record_id = record.id
        and audit.migration_key = 'V41_100_TO_200'
    ),
    hygiene_score = (
      select audit.converted_hygiene_score
      from inspection_score_scale_migration_audit audit
      where audit.tenant_id = record.tenant_id
        and audit.inspection_record_id = record.id
        and audit.migration_key = 'V41_100_TO_200'
    ),
    service_score = (
      select audit.converted_service_score
      from inspection_score_scale_migration_audit audit
      where audit.tenant_id = record.tenant_id
        and audit.inspection_record_id = record.id
        and audit.migration_key = 'V41_100_TO_200'
    ),
    passed = (
      select audit.converted_passed
      from inspection_score_scale_migration_audit audit
      where audit.tenant_id = record.tenant_id
        and audit.inspection_record_id = record.id
        and audit.migration_key = 'V41_100_TO_200'
    ),
    result_code = (
      select audit.converted_result_code
      from inspection_score_scale_migration_audit audit
      where audit.tenant_id = record.tenant_id
        and audit.inspection_record_id = record.id
        and audit.migration_key = 'V41_100_TO_200'
    )
where exists (
  select 1
  from inspection_score_scale_migration_audit audit
  where audit.tenant_id = record.tenant_id
    and audit.inspection_record_id = record.id
    and audit.migration_key = 'V41_100_TO_200'
);

update inspection_record record
set pass_score = 180.00,
    passed = (
      select audit.converted_passed
      from inspection_score_scale_migration_audit audit
      where audit.tenant_id = record.tenant_id
        and audit.inspection_record_id = record.id
        and audit.migration_key = 'V41_RESULT_RECALC_200'
    ),
    result_code = (
      select audit.converted_result_code
      from inspection_score_scale_migration_audit audit
      where audit.tenant_id = record.tenant_id
        and audit.inspection_record_id = record.id
        and audit.migration_key = 'V41_RESULT_RECALC_200'
    )
where exists (
  select 1
  from inspection_score_scale_migration_audit audit
  where audit.tenant_id = record.tenant_id
    and audit.inspection_record_id = record.id
    and audit.migration_key = 'V41_RESULT_RECALC_200'
);

update inspection_record
set pass_score = 180.00
where pass_score <> 180.00;

alter table inspection_record alter column full_score set default 200.00;
alter table inspection_record alter column pass_score set default 180.00;
alter table inspection_record alter column score set default 200.00;
alter table inspection_standard_version alter column full_score set default 200.00;
alter table inspection_standard_version alter column pass_score set default 180.00;

alter table inspection_record
  add constraint chk_inspection_record_score_scale
  check (
    full_score = 200.00
    and pass_score = 180.00
    and score >= 0.00
    and score <= 200.00
    and passed = case
      when upper(coalesce(result_code, '')) = 'RED_LINE_FAILED'
        or lower(trim(coalesce(redlines_json, ''))) not in ('', '[]', 'null') then 0
      when score >= 180.00 then 1
      else 0
    end
  );

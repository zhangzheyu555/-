-- V42: make V41 score conversion auditable and explicitly one-shot.
-- V40 is already deployed and V41 is an occupied migration. This migration is append-only.
-- V41's converted 200-point values remain persisted. Original values stay immutable
-- in the audit tables and must never be multiplied again.

alter table inspection_score_scale_migration_audit
  add column conversion_version varchar(32) null after migration_key;

alter table inspection_score_scale_migration_audit
  add column conversion_status varchar(32) not null default 'NOT_APPLICABLE' after conversion_version;

alter table inspection_score_scale_migration_audit
  add column conversion_evidence varchar(255) not null default 'NOT_APPLICABLE' after conversion_status;

alter table inspection_score_scale_item_migration_audit
  add column conversion_version varchar(32) not null default '100_TO_200_V1' after inspection_snapshot_id;

update inspection_score_scale_migration_audit audit
set conversion_version = case
      when audit.migration_key = 'V41_100_TO_200' then '100_TO_200_V1'
      when audit.migration_key = 'V41_RESULT_RECALC_200' then 'RESULT_POLICY_200_V1'
      else null
    end,
    conversion_status = case
      when audit.migration_key = 'V41_RESULT_RECALC_200' then 'CONFIRMED_RECALCULATED'
      when audit.migration_key <> 'V41_100_TO_200' then 'NOT_APPLICABLE'
      when exists (
        select 1
        from inspection_result_repair_audit repair
        where repair.tenant_id = audit.tenant_id
          and repair.inspection_record_id = audit.inspection_record_id
          and repair.id = (
            select max(latest.id)
            from inspection_result_repair_audit latest
            where latest.tenant_id = audit.tenant_id
              and latest.inspection_record_id = audit.inspection_record_id
          )
          and repair.repair_status = 'MANUAL_REVIEW'
      ) then 'MANUAL_REVIEW'
      when exists (
        select 1
        from inspection_result_repair_audit repair
        where repair.tenant_id = audit.tenant_id
          and repair.inspection_record_id = audit.inspection_record_id
          and repair.id = (
            select max(latest.id)
            from inspection_result_repair_audit latest
            where latest.tenant_id = audit.tenant_id
              and latest.inspection_record_id = audit.inspection_record_id
          )
          and repair.repair_status = 'RECALCULATED'
      ) then 'CONFIRMED_REPAIRED'
      when audit.original_full_score = 100.00
        and audit.original_score between 0.00 and 100.00
        and abs(audit.converted_score - round(audit.original_score * 2, 2)) <= 0.01
        and (audit.original_material_score is null
          or abs(audit.converted_material_score - round(audit.original_material_score * 2, 2)) <= 0.01)
        and (audit.original_hygiene_score is null
          or abs(audit.converted_hygiene_score - round(audit.original_hygiene_score * 2, 2)) <= 0.01)
        and (audit.original_service_score is null
          or abs(audit.converted_service_score - round(audit.original_service_score * 2, 2)) <= 0.01)
        and not exists (
          select 1
          from inspection_record record
          join inspection_standard_version version on version.id = record.standard_version_id
          where record.tenant_id = audit.tenant_id
            and record.id = audit.inspection_record_id
            and version.full_score = 200.00
        )
        and not exists (
          select 1
          from inspection_record_standard_snapshot snapshot
          join inspection_standard_item item on item.id = snapshot.standard_id
          join inspection_standard_version version on version.id = item.standard_version_id
          where snapshot.tenant_id = audit.tenant_id
            and snapshot.inspection_record_id = audit.inspection_record_id
            and version.full_score = 200.00
        )
        and not exists (
          select 1
          from inspection_score_scale_item_migration_audit item_audit
          where item_audit.score_migration_audit_id = audit.id
            and (
              abs(item_audit.converted_suggested_score
                - round(item_audit.original_suggested_score * 2, 2)) > 0.01
              or abs(item_audit.converted_deduction_score
                - round(item_audit.original_deduction_score * 2, 2)) > 0.01
              or abs(item_audit.converted_actual_score
                - round(item_audit.original_actual_score * 2, 2)) > 0.01
            )
        ) then 'CONFIRMED_CONVERTED'
      else 'MANUAL_REVIEW'
    end;

update inspection_score_scale_migration_audit
set conversion_evidence = case conversion_status
  when 'CONFIRMED_CONVERTED' then 'EXPLICIT_100_SCALE_AND_EXACT_X2'
  when 'CONFIRMED_REPAIRED' then 'EXISTING_RECALCULATED_REPAIR'
  when 'CONFIRMED_RECALCULATED' then 'EXISTING_200_SCALE_RESULT_RECALCULATION'
  when 'MANUAL_REVIEW' then 'CONFLICTING_OR_INCOMPLETE_SCALE_EVIDENCE'
  else 'NOT_APPLICABLE'
end;

-- Conflicting scale evidence is never guessed. A manual-review audit makes list,
-- detail, statistics and export use the same explicit unresolved result.
insert into inspection_result_repair_audit(
  tenant_id, inspection_record_id,
  original_standard_version_id, original_standard_version,
  original_full_score, original_pass_score, original_score,
  original_material_score, original_hygiene_score, original_service_score,
  original_result_code, original_passed,
  repaired_standard_version_id, repaired_standard_version,
  repaired_full_score, repaired_pass_score, repaired_score,
  repaired_material_score, repaired_hygiene_score, repaired_service_score,
  repaired_result_code, repaired_passed,
  repair_status, repair_reason, snapshot_item_count, expected_item_count,
  repaired_by, repaired_at
)
select audit.tenant_id,
       audit.inspection_record_id,
       record.standard_version_id,
       record.standard_version,
       audit.original_full_score,
       audit.original_pass_score,
       audit.original_score,
       audit.original_material_score,
       audit.original_hygiene_score,
       audit.original_service_score,
       audit.original_result_code,
       audit.original_passed,
       target.id,
       target.version,
       null, null, null,
       null, null, null,
       null, null,
       'MANUAL_REVIEW',
       'V42发现100分制记录关联200分制或不完整换算证据，已保留原始成绩并等待人工复核',
       (
         select count(*)
         from inspection_record_standard_snapshot snapshot
         where snapshot.tenant_id = audit.tenant_id
           and snapshot.inspection_record_id = audit.inspection_record_id
       ),
       (
         select count(*)
         from inspection_standard_item expected
         where expected.standard_version_id = target.id
           and expected.enabled = 1
       ),
       null,
       current_timestamp
from inspection_score_scale_migration_audit audit
join inspection_record record
  on record.tenant_id = audit.tenant_id
 and record.id = audit.inspection_record_id
join inspection_standard_version target
  on target.id = (
    select max(candidate.id)
    from inspection_standard_version candidate
    where candidate.tenant_id = audit.tenant_id
      and candidate.status = 'ACTIVE'
  )
where audit.migration_key = 'V41_100_TO_200'
  and audit.conversion_status = 'MANUAL_REVIEW'
  and not exists (
    select 1
    from inspection_result_repair_audit existing
    where existing.tenant_id = audit.tenant_id
      and existing.inspection_record_id = audit.inspection_record_id
  );

create index idx_inspection_score_conversion_status
  on inspection_score_scale_migration_audit(tenant_id, conversion_status, migrated_at);

alter table inspection_score_scale_migration_audit
  add constraint chk_inspection_score_conversion_status
  check (conversion_status in (
    'NOT_APPLICABLE',
    'CONFIRMED_CONVERTED',
    'CONFIRMED_REPAIRED',
    'CONFIRMED_RECALCULATED',
    'MANUAL_REVIEW'
  ));

drop procedure if exists v42_assert_inspection_score_conversion;

delimiter $$

create procedure v42_assert_inspection_score_conversion()
begin
  if exists (
    select 1
    from inspection_score_scale_migration_audit audit
    where audit.migration_key in ('V41_100_TO_200', 'V41_RESULT_RECALC_200')
      and (audit.conversion_version is null or audit.conversion_status = 'NOT_APPLICABLE')
  ) then
    signal sqlstate '45000'
      set message_text = 'V42 found an unversioned inspection score conversion';
  end if;

  if exists (
    select 1
    from inspection_score_scale_item_migration_audit item_audit
    join inspection_record_standard_snapshot snapshot
      on snapshot.id = item_audit.inspection_snapshot_id
    where abs(snapshot.suggested_score - item_audit.converted_suggested_score) > 0.01
       or abs(snapshot.actual_deduction_score - item_audit.converted_deduction_score) > 0.01
       or abs(snapshot.actual_score - item_audit.converted_actual_score) > 0.01
  ) then
    signal sqlstate '45000'
      set message_text = 'V42 found a snapshot outside its one-time 200-point conversion';
  end if;

  if exists (
    select 1
    from inspection_score_scale_migration_audit audit
    where audit.migration_key = 'V41_100_TO_200'
      and audit.conversion_status = 'MANUAL_REVIEW'
      and not exists (
        select 1
        from inspection_result_repair_audit repair
        where repair.tenant_id = audit.tenant_id
          and repair.inspection_record_id = audit.inspection_record_id
          and repair.repair_status = 'MANUAL_REVIEW'
      )
  ) then
    signal sqlstate '45000'
      set message_text = 'V42 found an unresolved inspection without manual-review audit';
  end if;
end$$

delimiter ;

call v42_assert_inspection_score_conversion();
drop procedure v42_assert_inspection_score_conversion;

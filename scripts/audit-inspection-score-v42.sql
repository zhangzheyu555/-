-- Read-only verification for V41/V42 inspection score conversion.
-- Run only against an isolated MySQL 8 copy after Flyway reaches V42.
-- This script contains no credentials and performs no writes.

select installed_rank, version, description, type, checksum, installed_on, success
from flyway_schema_history
where version in ('40', '41', '42')
order by installed_rank;

select migration_key,
       conversion_version,
       conversion_status,
       conversion_evidence,
       count(*) as record_count
from inspection_score_scale_migration_audit
group by migration_key, conversion_version, conversion_status, conversion_evidence
order by migration_key, conversion_status, conversion_evidence;

select conversion_version,
       count(*) as snapshot_count
from inspection_score_scale_item_migration_audit
group by conversion_version
order by conversion_version;

-- Original and converted values remain side by side in the item audit; this
-- aggregate proves the expected x2 relationship without exposing business rows.
select count(*) as item_audit_x2_mismatch_count
from inspection_score_scale_item_migration_audit
where abs(converted_suggested_score - round(original_suggested_score * 2, 2)) > 0.01
   or abs(converted_deduction_score - round(original_deduction_score * 2, 2)) > 0.01
   or abs(converted_actual_score - round(original_actual_score * 2, 2)) > 0.01;

-- Must return zero: live snapshots retain V41's one-time 200-point values.
select count(*) as snapshot_converted_mismatch_count
from inspection_score_scale_item_migration_audit item_audit
join inspection_record_standard_snapshot snapshot
  on snapshot.id = item_audit.inspection_snapshot_id
where abs(snapshot.suggested_score - item_audit.converted_suggested_score) > 0.01
   or abs(snapshot.actual_deduction_score - item_audit.converted_deduction_score) > 0.01
   or abs(snapshot.actual_score - item_audit.converted_actual_score) > 0.01;

-- Must return zero: every explicit legacy conversion has the one-time marker.
select count(*) as unversioned_conversion_count
from inspection_score_scale_migration_audit
where migration_key in ('V41_100_TO_200', 'V41_RESULT_RECALC_200')
  and (conversion_version is null or conversion_status = 'NOT_APPLICABLE');

-- Must return zero: a confirmed conversion must still satisfy the exact x2 evidence.
select count(*) as confirmed_conversion_evidence_mismatch_count
from inspection_score_scale_migration_audit
where conversion_status = 'CONFIRMED_CONVERTED'
  and (
    original_full_score <> 100.00
    or abs(converted_score - round(original_score * 2, 2)) > 0.01
    or (original_material_score is not null
      and abs(converted_material_score - round(original_material_score * 2, 2)) > 0.01)
    or (original_hygiene_score is not null
      and abs(converted_hygiene_score - round(original_hygiene_score * 2, 2)) > 0.01)
    or (original_service_score is not null
      and abs(converted_service_score - round(original_service_score * 2, 2)) > 0.01)
  );

-- Must return zero: ambiguous evidence is never guessed and always has a manual-review audit.
select count(*) as manual_review_without_repair_audit_count
from inspection_score_scale_migration_audit audit
where audit.migration_key = 'V41_100_TO_200'
  and audit.conversion_status = 'MANUAL_REVIEW'
  and not exists (
    select 1
    from inspection_result_repair_audit repair
    where repair.tenant_id = audit.tenant_id
      and repair.inspection_record_id = audit.inspection_record_id
      and repair.repair_status = 'MANUAL_REVIEW'
  );

select repair_status, count(*) as record_count
from inspection_result_repair_audit
group by repair_status
order by repair_status;

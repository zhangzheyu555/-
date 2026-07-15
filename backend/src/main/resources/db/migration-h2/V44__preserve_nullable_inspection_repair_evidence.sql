-- H2 equivalent of V44. Preserve absence of original score evidence exactly.

alter table inspection_result_repair_audit alter column original_full_score drop not null;
alter table inspection_result_repair_audit alter column original_score drop not null;
alter table inspection_result_repair_audit alter column original_passed drop not null;

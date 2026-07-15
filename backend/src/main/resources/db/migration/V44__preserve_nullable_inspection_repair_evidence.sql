-- V44: An absent historical score is evidence, not a zero score or a failed result.
-- Keep V40 audit rows immutable while allowing later deterministic repairs to preserve nulls.

alter table inspection_result_repair_audit
  modify column original_full_score decimal(8,2) null,
  modify column original_score decimal(8,2) null,
  modify column original_passed tinyint(1) null;

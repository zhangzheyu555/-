-- H2 equivalent of V53__exam_retake_cooldown.sql.

alter table training_exam_assignment
  add column retake_available_at timestamp null;

create index idx_exam_assignment_retake
  on training_exam_assignment(tenant_id, user_id, retake_available_at);

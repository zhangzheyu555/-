-- V53: a screen-switch violation invalidates the attempt and delays the next retake.

alter table training_exam_assignment
  add column retake_available_at timestamp null after completed_at;

create index idx_exam_assignment_retake
  on training_exam_assignment(tenant_id, user_id, retake_available_at);

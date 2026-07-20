-- V56 used MODIFY COLUMN to add a Chinese comment and unintentionally removed
-- the optimistic-lock default introduced by V43. Restore the schema contract so
-- every insert path starts a requisition at version zero.
alter table store_requisition
  modify column version bigint not null default 0 comment '版本号';

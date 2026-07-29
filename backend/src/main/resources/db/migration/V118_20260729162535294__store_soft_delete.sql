-- Flyway version: 118.20260729162535294
-- Purpose: store_soft_delete
-- Preserve store history while allowing retired stores to leave operational selectors.

alter table store_branch
  add column deleted_at timestamp null after updated_at,
  add column deleted_by bigint null after deleted_at;

create index idx_store_branch_tenant_deleted
  on store_branch(tenant_id, deleted_at);

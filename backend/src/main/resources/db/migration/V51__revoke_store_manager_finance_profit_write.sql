-- ============================================================
-- V51: Revoke finance.profit.write from STORE_MANAGER
-- ============================================================
-- Per the 2026-07-14 governance decision:
--   Store managers may ONLY read their own store's profit data.
--   All data entry, monthly import, modification and deletion
--   move exclusively to FINANCE and BOSS.
--
-- This migration:
--   1. Removes the role-level grant so NEW store managers never
--      receive the write permission.
--   2. Identifies any per-user ALLOW overrides and writes an
--      audit trail entry but does NOT auto-delete them.  (The
--      backend AccessControlService now enforces a role-boundary
--      check that overrides any stale personal ALLOW.)
--   3. Does NOT delete business data, accounts, or operation logs.
--   4. Preserves all other STORE_MANAGER permissions unchanged
--      (expense, salary read, warehouse store, requisition, etc.).

-- 1. Remove the role-level grant
delete from role_permission
where upper(role_code) = 'STORE_MANAGER'
  and permission_code = 'finance.profit.write';

-- 2. Audit: note any existing per-user ALLOW overrides for the
--    revoked permission.  These are informational only; the
--    Java access-control layer will reject them at runtime.
insert into operation_log (
  tenant_id, operator_id, operator_name, action,
  target_type, target_id, store_id, month, reason, created_at
)
select
  up.tenant_id,
  null,
  'SYSTEM (V50 migration)',
  'AUDIT_REVOKE',
  'user_permission',
  concat('finance.profit.write@user-', up.user_id),
  null,
  null,
  concat(
    'Migration V50: detected personal ALLOW for finance.profit.write on user ',
    up.user_id,
    '; the backend enforces a BOSS-or-FINANCE role boundary at runtime.'
  ),
  current_timestamp
from user_permission_override up
join auth_user au
  on au.tenant_id = up.tenant_id
 and au.id = up.user_id
where upper(au.role) = 'STORE_MANAGER'
  and up.permission_code = 'finance.profit.write'
  and up.effect = 'ALLOW';

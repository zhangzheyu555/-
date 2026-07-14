update auth_user
set role = 'BOSS',
    store_id = null
where upper(role) in ('ADMIN', 'OWNER');

delete scope
from user_store_scope scope
join auth_user user_account
  on user_account.tenant_id = scope.tenant_id
 and user_account.id = scope.user_id
where user_account.role = 'BOSS';

delete from role_permission
where upper(role_code) in ('ADMIN', 'OWNER');

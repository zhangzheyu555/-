alter table auth_user
  add column password_change_required boolean not null default false;

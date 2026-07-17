-- H2 等价版：员工档案扩展（与 db/migration/V66 同步维护，H2 不写 comment/after 子句）。
alter table employee add column birthday varchar(20) null;
alter table employee add column id_card_no varchar(30) null;
alter table employee add column health_cert_issue_date date null;
alter table employee add column health_cert_expire_date date null;
alter table employee add column contract_sign_text varchar(60) null;
alter table employee add column regular_date date null;
alter table employee add column trainer_date date null;
alter table employee add column shift_leader_date date null;
alter table employee add column manager_date date null;
alter table employee add column auth_user_id bigint null;

alter table employee add constraint uk_employee_auth_user unique (auth_user_id);
alter table employee add constraint fk_employee_auth_user foreign key (auth_user_id) references auth_user(id);

create index idx_employee_health_expire on employee (tenant_id, health_cert_expire_date);

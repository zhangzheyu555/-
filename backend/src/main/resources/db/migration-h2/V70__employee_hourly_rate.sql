-- V70: 员工时薪选择（兼职/实习/长期阿姨适用）。空=按默认口径（实习兼职15、长期阿姨正式18，工资模板新版）。
-- H2 does not support MySQL's COMMENT and AFTER clauses.
alter table employee add column hourly_rate decimal(8,2);

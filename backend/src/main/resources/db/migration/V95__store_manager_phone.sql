-- Store the verified contact number from the real store master workbook as a first-class field.
alter table store_branch
  add column manager_phone varchar(40) null after manager;

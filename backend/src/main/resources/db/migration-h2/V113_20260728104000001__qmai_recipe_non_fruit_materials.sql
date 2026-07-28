-- H2 verification counterpart for MySQL V113.
alter table qmai_recipe_ingredient
  drop constraint chk_qmai_recipe_kind;

alter table qmai_recipe_ingredient
  alter column fruit_name varchar(160) null;

alter table qmai_recipe_ingredient
  add constraint chk_qmai_recipe_kind
    check (conversion_kind in ('FLESH', 'JUICE', 'ONE', 'NONE'));

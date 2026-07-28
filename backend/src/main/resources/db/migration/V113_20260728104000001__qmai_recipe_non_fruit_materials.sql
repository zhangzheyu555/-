-- Preserve the user's original material-usage rules: recipes may include water, coconut milk,
-- sugar syrup and other non-fruit materials that are summed without fruit-yield conversion.
alter table qmai_recipe_ingredient
  drop check chk_qmai_recipe_kind;

alter table qmai_recipe_ingredient
  modify fruit_name varchar(160) null;

alter table qmai_recipe_ingredient
  add constraint chk_qmai_recipe_kind
    check (conversion_kind in ('FLESH', 'JUICE', 'ONE', 'NONE'));

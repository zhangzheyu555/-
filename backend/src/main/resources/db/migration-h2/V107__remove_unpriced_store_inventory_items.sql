-- H2 verification equivalent of MySQL V107.
-- Historical store_inventory_check_line rows remain untouched.
delete from store_inventory_item
where item_code in (
  'PD-HC-032',
  'PD-SG-017',
  'PD-SG-018',
  'PD-SG-019',
  'PD-SG-020',
  'PD-SG-021'
);

-- Remove the six unpriced rows from every tenant's active store-inventory catalog.
-- Historical store_inventory_check_line rows are immutable snapshots and are intentionally
-- retained; they do not have a foreign key to this catalog.
delete from store_inventory_item
where item_code in (
  'PD-HC-032',
  'PD-SG-017',
  'PD-SG-018',
  'PD-SG-019',
  'PD-SG-020',
  'PD-SG-021'
);

-- Retire the removed QMAI daily-inventory feature and its persisted snapshots.
drop table if exists qmai_inventory_snapshot_item;
drop table if exists qmai_inventory_sync_lease;
drop table if exists qmai_inventory_snapshot;

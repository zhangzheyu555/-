-- Update only the currently effective standard. Historical inspection snapshots remain immutable.
update inspection_standard_item item
join inspection_standard_version version on version.id = item.standard_version_id
set item.title = '食品原料与非食品用品分区存放',
    item.description = '茶叶、糖浆、奶制品、水果、珍珠等食品原料及成品配料应分类密封存放；清洁剂、消毒剂和私人物品必须设置独立区域，不得与食品同柜混放。',
    item.updated_at = current_timestamp
where version.status = 'ACTIVE'
  and item.enabled = 1
  and (
    item.code = 'M03'
    or concat(coalesce(item.title, ''), coalesce(item.description, '')) regexp '生熟|生肉|熟肉'
  );

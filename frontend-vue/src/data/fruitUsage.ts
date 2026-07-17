// 由 /tmp/gen_fruit_usage.py 从桌面《5月产品用量核算表》自动生成，改配方请改表后重新生成。
// 口径：grams=每杯该原料用量(克)；fruit=归属水果；kind: flesh=净肉(÷出肉率折毛重)、
//       juice=按酱汁表系数折毛重(factor=每克汁的水果毛重)、one=无出肉率数据按1:1、none=非水果物料。
export interface RecipeIngredient { label: string; grams: number; fruit: string | null; kind: 'flesh' | 'juice' | 'one' | 'none'; factor?: number }
export interface Recipe { name: string; baseName?: string; ingredients: RecipeIngredient[] }

/** 水果出肉率（毛重→净肉），来自「水果出肉率」工作表 */
export const FRUIT_YIELD: Record<string, number> = {
  '牛油果': 0.78,
  '芒果': 0.530303,
  '火龙果': 0.7,
  '桃子': 0.742268,
  '黄皮': 0.740408,
  '葡萄': 0.65,
  '凤梨': 0.598264,
  '杨梅': 0.8,
  '青芒': 0.7,
  '西瓜': 0.8,
  '橙子': 0.54,
  '柠檬': 0.9,
  '芭乐': 0.735676,
  '荔枝': 0.580448,
  '莲雾': 0.9,
  '雪梨': 0.788623,
  '苹果': 0.897855
}

/** 无出肉率数据、按 1:1 折算的水果（结果偏保守，页面已标注） */
export const NO_YIELD_FRUITS = ['榴莲', '百香果', '羊角蜜', '羽衣甘蓝', '耙耙柑']

export const RECIPES: Recipe[] = [
  { name: '芒芒甘露', ingredients: [
    { label: '芒果粒', grams: 50, fruit: '芒果', kind: 'flesh' },
    { label: '芒果', grams: 170, fruit: '芒果', kind: 'flesh' },
    { label: '水', grams: 55, fruit: null, kind: 'none' },
    { label: '椰奶', grams: 225, fruit: null, kind: 'none' },
  ] },
  { name: '牛油果甘露（中杯）', baseName: '牛油果甘露', ingredients: [
    { label: '芒果粒', grams: 50, fruit: '芒果', kind: 'flesh' },
    { label: '牛油果', grams: 80, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '牛油果甘露（大杯）', baseName: '牛油果甘露', ingredients: [
    { label: '芒果粒', grams: 50, fruit: '芒果', kind: 'flesh' },
    { label: '牛油果', grams: 100, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '牛油果追芒芒（中杯）', baseName: '牛油果追芒芒', ingredients: [
    { label: '芒果肉', grams: 70, fruit: '芒果', kind: 'flesh' },
    { label: '牛油果', grams: 80, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '牛油果追芒芒（大杯）', baseName: '牛油果追芒芒', ingredients: [
    { label: '芒果肉', grams: 110, fruit: '芒果', kind: 'flesh' },
    { label: '牛油果', grams: 100, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '牛油果追凤梨', ingredients: [
    { label: '凤梨肉', grams: 70, fruit: '凤梨', kind: 'flesh' },
    { label: '牛油果', grams: 80, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '奇亚籽羽衣甘蓝牛油果', ingredients: [
    { label: '牛油果', grams: 60, fruit: '牛油果', kind: 'flesh' },
    { label: '羽衣甘蓝', grams: 7, fruit: '羽衣甘蓝', kind: 'one' },
  ] },
  { name: '牛油果追火龙果（中杯）', baseName: '牛油果追火龙果', ingredients: [
    { label: '火龙果肉', grams: 70, fruit: '火龙果', kind: 'flesh' },
    { label: '牛油果', grams: 80, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '牛油果追火龙果（大杯）', baseName: '牛油果追火龙果', ingredients: [
    { label: '火龙果肉', grams: 80, fruit: '火龙果', kind: 'flesh' },
    { label: '牛油果', grams: 100, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '茉香芝芝芒芒', ingredients: [
    { label: '芒果肉', grams: 180, fruit: '芒果', kind: 'flesh' },
  ] },
  { name: '春见与百香', ingredients: [
    { label: '橙子角', grams: 30, fruit: '橙子', kind: 'flesh' },
    { label: '百香果', grams: 30, fruit: '百香果', kind: 'one' },
    { label: '耙耙柑果肉', grams: 100, fruit: '耙耙柑', kind: 'one' },
    { label: '橙子肉', grams: 53, fruit: '橙子', kind: 'flesh' },
    { label: '凤梨肉', grams: 26, fruit: '凤梨', kind: 'flesh' },
    { label: '糖油', grams: 21, fruit: null, kind: 'none' },
  ] },
  { name: '手剥耙耙柑', ingredients: [
    { label: '耙耙柑果肉', grams: 120, fruit: '耙耙柑', kind: 'one' },
    { label: '橙子肉', grams: 53, fruit: '橙子', kind: 'flesh' },
    { label: '凤梨肉', grams: 26, fruit: '凤梨', kind: 'flesh' },
    { label: '糖油', grams: 21, fruit: null, kind: 'none' },
    { label: '橙子角', grams: 30, fruit: '橙子', kind: 'flesh' },
  ] },
  { name: '每日阳光甜橙冰(中)', ingredients: [
    { label: '橙子底肉', grams: 50, fruit: '橙子', kind: 'flesh' },
    { label: '橙子块', grams: 95, fruit: '橙子', kind: 'flesh' },
    { label: '梨汁', grams: 60, fruit: '雪梨', kind: 'juice', factor: 1.2650 },
  ] },
  { name: '每日阳光甜橙冰(大)', ingredients: [
    { label: '橙子底肉', grams: 80, fruit: '橙子', kind: 'flesh' },
    { label: '橙子块', grams: 190, fruit: '橙子', kind: 'flesh' },
    { label: '梨汁', grams: 120, fruit: '雪梨', kind: 'juice', factor: 1.2650 },
  ] },
  { name: '霸气橙子', ingredients: [
    { label: '橙子角', grams: 120, fruit: '橙子', kind: 'flesh' },
    { label: '橙子肉', grams: 69, fruit: '橙子', kind: 'flesh' },
    { label: '凤梨肉', grams: 34, fruit: '凤梨', kind: 'flesh' },
    { label: '糖油', grams: 27, fruit: null, kind: 'none' },
  ] },
  { name: '芭乐与莲雾', ingredients: [
    { label: '芭乐肉', grams: 86, fruit: '芭乐', kind: 'flesh' },
    { label: '白糖', grams: 17, fruit: null, kind: 'none' },
    { label: '苹果汁', grams: 17, fruit: '苹果', kind: 'juice', factor: 0.9790 },
    { label: '莲雾丁', grams: 40, fruit: '莲雾', kind: 'flesh' },
  ] },
  { name: '胭脂粉芭乐', ingredients: [
    { label: '芭乐肉', grams: 158, fruit: '芭乐', kind: 'flesh' },
    { label: '白糖', grams: 31, fruit: null, kind: 'none' },
    { label: '苹果汁', grams: 31, fruit: '苹果', kind: 'juice', factor: 0.9790 },
  ] },
  { name: '芒也', ingredients: [
    { label: '芒果粒', grams: 50, fruit: '芒果', kind: 'flesh' },
  ] },
  { name: '自然轻甜.小绿杯', ingredients: [
    { label: '羽衣甘蓝汁', grams: 20, fruit: '羽衣甘蓝', kind: 'juice', factor: 1.0000 },
    { label: '雪梨汁', grams: 90, fruit: '雪梨', kind: 'juice', factor: 1.2650 },
    { label: '橙汁', grams: 120, fruit: '橙子', kind: 'juice', factor: 1.5620 },
  ] },
  { name: '春天里的羊角蜜', ingredients: [
    { label: '羊角蜜', grams: 250, fruit: '羊角蜜', kind: 'one' },
  ] },
  { name: '夏日菠萝冰', ingredients: [
    { label: '凤梨肉', grams: 150, fruit: '凤梨', kind: 'flesh' },
    { label: '凤梨', grams: 56, fruit: '凤梨', kind: 'flesh' },
    { label: '糖油', grams: 14, fruit: null, kind: 'none' },
  ] },
  { name: '芒芒凤梨', ingredients: [
    { label: '芒果肉', grams: 130, fruit: '芒果', kind: 'flesh' },
    { label: '凤梨', grams: 80, fruit: '凤梨', kind: 'flesh' },
    { label: '糖油', grams: 20, fruit: null, kind: 'none' },
    { label: '柠檬角', grams: 10, fruit: '柠檬', kind: 'flesh' },
  ] },
  { name: '西瓜大战椰子', ingredients: [
    { label: '西瓜果肉', grams: 200, fruit: '西瓜', kind: 'flesh' },
  ] },
  { name: '夏日西瓜雪梨冰（中杯）', baseName: '夏日西瓜雪梨冰', ingredients: [
    { label: '西瓜果肉', grams: 260, fruit: '西瓜', kind: 'flesh' },
    { label: '雪梨汁', grams: 50, fruit: '雪梨', kind: 'juice', factor: 1.2650 },
  ] },
  { name: '夏日西瓜雪梨冰（大杯）', baseName: '夏日西瓜雪梨冰', ingredients: [
    { label: '西瓜果肉', grams: 500, fruit: '西瓜', kind: 'flesh' },
    { label: '雪梨汁', grams: 100, fruit: '雪梨', kind: 'juice', factor: 1.2650 },
  ] },
  { name: '满杯VC水果茶', ingredients: [
    { label: '芒果条', grams: 50, fruit: '芒果', kind: 'flesh' },
    { label: '西瓜果肉', grams: 80, fruit: '西瓜', kind: 'flesh' },
    { label: '百香果', grams: 15, fruit: '百香果', kind: 'one' },
    { label: '橙子肉', grams: 42, fruit: '橙子', kind: 'flesh' },
    { label: '凤梨肉', grams: 21, fruit: '凤梨', kind: 'flesh' },
    { label: '糖油', grams: 17, fruit: null, kind: 'none' },
    { label: '橙子角', grams: 30, fruit: '橙子', kind: 'flesh' },
  ] },
  { name: '凤梨百香火龙果', ingredients: [
    { label: '火龙果肉', grams: 140, fruit: '火龙果', kind: 'flesh' },
    { label: '凤梨', grams: 80, fruit: '凤梨', kind: 'flesh' },
    { label: '糖油', grams: 20, fruit: null, kind: 'none' },
    { label: '百香果', grams: 30, fruit: '百香果', kind: 'one' },
  ] },
  { name: '百香芒芒金凤梨', ingredients: [
    { label: '芒果肉', grams: 150, fruit: '芒果', kind: 'flesh' },
    { label: '凤梨', grams: 64, fruit: '凤梨', kind: 'flesh' },
    { label: '糖油', grams: 16, fruit: null, kind: 'none' },
    { label: '百香果', grams: 30, fruit: '百香果', kind: 'one' },
  ] },
  { name: '手打柠檬茶', ingredients: [
    { label: '柠檬角', grams: 80, fruit: '柠檬', kind: 'flesh' },
  ] },
  { name: '羽衣甘蓝柠檬茶', ingredients: [
    { label: '柠檬角', grams: 80, fruit: '柠檬', kind: 'flesh' },
    { label: '羽衣甘蓝汁', grams: 25, fruit: '羽衣甘蓝', kind: 'juice', factor: 1.0000 },
  ] },
  { name: '乘风破浪的阳光橙', ingredients: [
    { label: '橙子块', grams: 280, fruit: '橙子', kind: 'flesh' },
    { label: '雪梨块', grams: 170, fruit: '雪梨', kind: 'flesh' },
  ] },
  { name: '元气小红瓶', ingredients: [
    { label: '火龙果', grams: 50, fruit: '火龙果', kind: 'flesh' },
    { label: '凤梨', grams: 70, fruit: '凤梨', kind: 'flesh' },
    { label: '橙子', grams: 70, fruit: '橙子', kind: 'flesh' },
    { label: '苹果', grams: 70, fruit: '苹果', kind: 'flesh' },
    { label: '雪梨', grams: 150, fruit: '雪梨', kind: 'flesh' },
  ] },
  { name: '超绿轻体瓶', ingredients: [
    { label: '雪梨', grams: 250, fruit: '雪梨', kind: 'flesh' },
    { label: '橙子', grams: 120, fruit: '橙子', kind: 'flesh' },
    { label: '羽衣甘蓝', grams: 25, fruit: '羽衣甘蓝', kind: 'one' },
  ] },
  { name: '满杯百香果', ingredients: [
    { label: '百香果', grams: 60, fruit: '百香果', kind: 'one' },
  ] },
  { name: '青柠百香果', ingredients: [
    { label: '柠檬角', grams: 40, fruit: '柠檬', kind: 'flesh' },
    { label: '百香果', grams: 30, fruit: '百香果', kind: 'one' },
  ] },
  { name: '榴莲和芒果', ingredients: [
    { label: '榴莲肉', grams: 50, fruit: '榴莲', kind: 'one' },
    { label: '芒果粒', grams: 50, fruit: '芒果', kind: 'flesh' },
    { label: '新鲜芒果汁', grams: 120, fruit: '芒果', kind: 'juice', factor: 1.8867 },
    { label: '水', grams: 40, fruit: null, kind: 'none' },
    { label: '椰奶', grams: 160, fruit: null, kind: 'none' },
  ] },
  { name: '黄金榴莲牛油果', ingredients: [
    { label: '榴莲肉', grams: 50, fruit: '榴莲', kind: 'one' },
    { label: '牛油果', grams: 80, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '大块芒果酸奶冰', ingredients: [
    { label: '芒果肉', grams: 150, fruit: '芒果', kind: 'flesh' },
  ] },
  { name: '大块火龙酸奶冰', ingredients: [
    { label: '火龙果', grams: 170, fruit: '火龙果', kind: 'flesh' },
  ] },
  { name: '霸气桃桃水果茶', ingredients: [
    { label: '桃子肉', grams: 225, fruit: '桃子', kind: 'flesh' },
  ] },
  { name: '芭乐桃桃', ingredients: [
    { label: '芭乐果肉', grams: 96, fruit: '芭乐', kind: 'flesh' },
    { label: '桃子肉', grams: 105, fruit: '桃子', kind: 'flesh' },
  ] },
  { name: '牛油果追桃桃（中杯）', baseName: '牛油果追桃桃', ingredients: [
    { label: '桃子肉', grams: 80, fruit: '桃子', kind: 'flesh' },
    { label: '牛油果', grams: 80, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '牛油果追桃桃（大杯）', baseName: '牛油果追桃桃', ingredients: [
    { label: '桃子肉', grams: 100, fruit: '桃子', kind: 'flesh' },
    { label: '牛油果', grams: 100, fruit: '牛油果', kind: 'flesh' },
  ] },
  { name: '芝士桃桃冰', ingredients: [
    { label: '桃子肉', grams: 232, fruit: '桃子', kind: 'flesh' },
  ] },
  { name: '满杯桃桃冰冰茶', ingredients: [
    { label: '桃子肉', grams: 232, fruit: '桃子', kind: 'flesh' },
  ] },
  { name: '芝士火龙果', ingredients: [
    { label: '火龙果', grams: 180, fruit: '火龙果', kind: 'flesh' },
  ] },
  { name: '厚椰芒芒冰茶', ingredients: [
    { label: '芒果', grams: 300, fruit: '芒果', kind: 'flesh' },
  ] },
  { name: '杨梅凤梨', ingredients: [
    { label: '杨梅', grams: 144, fruit: '杨梅', kind: 'flesh' },
    { label: '凤梨汁', grams: 64, fruit: '凤梨', kind: 'juice', factor: 1.3372 },
    { label: '糖油', grams: 16, fruit: null, kind: 'none' },
  ] },
  { name: '芝士杨梅冰', ingredients: [
    { label: '杨梅', grams: 161, fruit: '杨梅', kind: 'flesh' },
  ] },
  { name: '满杯杨梅冰冰茶', ingredients: [
    { label: '杨梅', grams: 152, fruit: '杨梅', kind: 'flesh' },
  ] },
  { name: '蜜桃荔枝水果茶', ingredients: [
    { label: '桃子肉', grams: 115, fruit: '桃子', kind: 'flesh' },
    { label: '荔枝肉', grams: 100, fruit: '荔枝', kind: 'flesh' },
    { label: '荔枝汁', grams: 60, fruit: '荔枝', kind: 'juice', factor: 1.0000 },
  ] },
  { name: '爆汁西瓜桃桃冰', ingredients: [
    { label: '桃桃', grams: 100, fruit: '桃子', kind: 'flesh' },
    { label: '西瓜', grams: 170, fruit: '西瓜', kind: 'flesh' },
  ] },
  { name: '芭乐荔枝', ingredients: [
    { label: '芭乐果肉', grams: 96, fruit: '芭乐', kind: 'flesh' },
    { label: '荔枝肉', grams: 80, fruit: '荔枝', kind: 'flesh' },
  ] },
  { name: '荔枝西瓜冰', ingredients: [
    { label: '荔枝肉', grams: 80, fruit: '荔枝', kind: 'flesh' },
    { label: '荔枝汁', grams: 50, fruit: '荔枝', kind: 'juice', factor: 1.0000 },
    { label: '西瓜肉', grams: 80, fruit: '西瓜', kind: 'flesh' },
  ] },
  { name: '芝芝粉荔', ingredients: [
    { label: '荔枝肉', grams: 120, fruit: '荔枝', kind: 'flesh' },
    { label: '荔枝汁', grams: 100, fruit: '荔枝', kind: 'juice', factor: 1.0000 },
  ] },
  { name: '荔夏，茉莉', ingredients: [
    { label: '荔枝汁', grams: 150, fruit: '荔枝', kind: 'juice', factor: 1.0000 },
  ] },
]

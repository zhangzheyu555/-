export type Brand = '茹果' | '霸王茶姬' | '瑞幸咖啡';

export interface StoreSummary {
  id: string;
  code: string;
  name: string;
  brand: Brand;
  area: string;
  manager: string;
  status: '营业中' | '待开业' | '已停业';
  sales: number;
  net: number;
  margin: number;
  risk: 'good' | 'warn' | 'bad';
}

export const months = ['2026-07', '2026-06', '2026-05', '2026-04'];

export const stores: StoreSummary[] = [
  { id: 'rg1', code: 'RG001', name: '保利店', brand: '茹果', area: '荆州', manager: '李瑜', status: '营业中', sales: 538253, net: 89240, margin: 0.166, risk: 'good' },
  { id: 'rg4', code: 'RG004', name: '荆州之星店', brand: '茹果', area: '荆州', manager: '孔繁丽', status: '营业中', sales: 761910, net: 118420, margin: 0.155, risk: 'good' },
  { id: 'rg8', code: 'RG008', name: '长大店', brand: '茹果', area: '荆州', manager: '周雨', status: '营业中', sales: 176542, net: 21300, margin: 0.121, risk: 'warn' },
  { id: 'bw2', code: 'BW002', name: '万达店', brand: '霸王茶姬', area: '汕头', manager: '田琴琴', status: '营业中', sales: 421880, net: 62410, margin: 0.148, risk: 'good' },
  { id: 'bw5', code: 'BW005', name: '环美店', brand: '霸王茶姬', area: '汕头', manager: '肖志凤', status: '营业中', sales: 310226, net: -18400, margin: -0.059, risk: 'bad' },
  { id: 'rx3', code: 'RX003', name: '长江大学店', brand: '瑞幸咖啡', area: '荆州', manager: '陈晨', status: '营业中', sales: 253640, net: 38610, margin: 0.152, risk: 'good' }
];

export const assistantReplies = [
  '保利店 2026 年 5 月营业额为 538,253 元，净利润 89,240 元，净利率 16.6%。',
  '当前样例数据中，环美店为亏损门店，净利润 -18,400 元。',
  '荆州之星店样例净利润最高，为 118,420 元。'
];

# 工资策略设计文档

版本: 1.0 | 日期: 2026-07-10

---

## 1. 设计目标

将旧版硬编码的工资公式（保底、加班、提成）替换为可配置、可版本化、可审计的工资规则系统。

核心原则：
- 工资规则不写在代码里，而是存储在数据库中
- 每次工资计算保存规则版本号和明细
- 历史工资不随新规则变化
- 计算过程可追溯、可解释、可审计

---

## 2. 数据模型

### 2.1 salary_policy（工资政策）

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(120) PK | 政策唯一标识 |
| tenant_id | bigint | 租户 |
| name | varchar(160) | 政策名称，如 "2026年标准工资规则" |
| version | int | 版本号，修改规则时递增 |
| effective_from | date | 生效日期 |
| effective_to | date | 失效日期（null = 长期有效） |
| status | varchar(40) | DRAFT / ACTIVE / ARCHIVED |
| guarantee_enabled | tinyint(1) | 是否启用保底工资 |
| guarantee_full_attendance_days | decimal(4,1) | 享受保底的最小出勤天数 |
| guarantee_included_items | varchar(500) | 保底计算包含的工资项目编码（逗号分隔） |
| guarantee_excluded_items | varchar(500) | 保底计算排除的项目编码 |
| guarantee_feb_exclude | tinyint(1) | 2月是否排除保底计算 |
| overtime_hour_rate | decimal(14,2) | 默认加班小时单价 |
| overtime_hour_rate_source | varchar(40) | 加班数据来源 |

### 2.2 employee_salary_profile（员工工资档案）

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(120) PK | 档案唯一标识 |
| employee_id | varchar(120) FK | 关联 employee.id |
| policy_id | varchar(120) FK | 关联 salary_policy.id |
| base_salary | decimal(14,2) | 月基本工资 |
| guarantee_salary | decimal(14,2) | 保底工资金额（null = 无保底） |
| overtime_hour_rate | decimal(14,2) | 该员工加班小时单价 |
| performance_type | varchar(40) | 绩效类型: FIXED_PERCENT / TIERED / NONE |
| commission_type | varchar(40) | 提成类型: REVENUE_PCT / PROFIT_PCT / FIXED / NONE |
| effective_from | date | 生效日期 |
| effective_to | date | 失效日期 |

### 2.3 salary_record_item（工资明细行）

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(120) PK | 明细唯一标识 |
| salary_record_id | varchar(120) FK | 关联 salary_record.id |
| item_code | varchar(40) | 项目编码: BASE, OVERTIME, COMMISSION 等 |
| item_name | varchar(120) | 项目中文名 |
| item_type | varchar(20) | EARNING / DEDUCTION / EMPLOYER_COST / INFORMATION |
| quantity | decimal(14,4) | 数量（如加班小时、出勤天数） |
| unit_price | decimal(14,4) | 单价（如小时工资、日工资） |
| amount | decimal(14,2) | 项目金额 |
| source | varchar(40) | 数据来源: MANUAL / ATTENDANCE / PERFORMANCE / CALCULATED |
| calculation_note | varchar(500) | 计算说明 |

---

## 3. 工资项目类型

### 3.1 EARNING（收入）
| 编码 | 名称 | 说明 |
|------|------|------|
| BASE | 基本工资 | 月固定工资 |
| ATTENDANCE | 考勤工资 | 按出勤天数计算的工资 |
| POST | 岗位工资 | 岗位津贴 |
| MEAL | 餐补 | 餐饮补贴 |
| FULL_ATTENDANCE | 全勤 | 全勤奖金 |
| OVERTIME | 加班工资 | 加班小时 * 小时单价 |
| COMMISSION | 提成 | 销售/利润提成 |
| SENIORITY | 工龄工资 | 按工龄递增 |
| LATE_NIGHT | 深夜班 | 深夜班次补贴 |
| SUBSIDY | 补贴 | 其他补贴 |
| PERFORMANCE | 绩效 | 绩效考核奖金 |
| BONUS | 奖金 | 一次性奖金 |
| RETURN_UNIFORM | 返工服费 | 退还工服押金 |

### 3.2 DEDUCTION（扣款）
| 编码 | 名称 | 说明 |
|------|------|------|
| SOCIAL_INSURANCE | 社保个人部分 | 养老保险+医疗保险+失业保险 |
| HOUSING_FUND | 公积金个人部分 | 住房公积金 |
| INCOME_TAX | 个税 | 个人所得税 |
| ABSENCE | 缺勤扣款 | 按缺勤天数扣除 |
| DEDUCT_UNIFORM | 扣工服费 | 工服押金/折旧 |
| OTHER_DEDUCTION | 其他扣款 | 其他扣款项 |

### 3.3 EMPLOYER_COST（公司成本）
| 编码 | 名称 | 说明 |
|------|------|------|
| EMPLOYER_SOCIAL | 公司社保 | 公司承担的社保部分 |
| EMPLOYER_HOUSING | 公司公积金 | 公司承担的公积金 |

### 3.4 INFORMATION（仅展示）
| 编码 | 名称 | 说明 |
|------|------|------|
| ATTENDANCE_DAYS | 出勤天数 | 本月实际出勤 |
| OVERTIME_HOURS | 加班小时 | 加班总小时数 |
| VACATION_LEFT | 剩余假期 | 假期余额 |
| NORMAL_HOURS | 标准工时 | 标准工作小时 |

---

## 4. 保底工资设计

### 4.1 启用条件
1. `salary_policy.guarantee_enabled = true`
2. 工资月份不是2月（当 `guarantee_feb_exclude = true`）
3. 员工当月出勤天数 >= `guarantee_full_attendance_days`

### 4.2 计算逻辑
```
应发合计 = sum(EARNING 类项目金额)
保底差额 = employee_salary_profile.guarantee_salary - 保底基准合计
最终应发 = max(应发合计, 应发合计 + 保底差额)
```

### 4.3 保底基准
保底仅对 `guarantee_included_items` 中指定的项目进行比较：
- 默认包含: BASE, POST, FULL_ATTENDANCE, MEAL
- 默认不包含: COMMISSION, OVERTIME, SUBSIDY, PERFORMANCE, BONUS

### 4.4 保底明细
当保底生效时，新增一条 salary_record_item：
- item_code: GUARANTEE_TOPUP
- item_name: 保底补差
- item_type: EARNING
- amount: 保底差额
- source: CALCULATED
- calculation_note: 包含保底计算过程和政策版本

---

## 5. 加班工资设计

### 5.1 加班小时单价来源（优先级）
1. `employee_salary_profile.overtime_hour_rate`（员工档案）
2. `salary_policy.overtime_hour_rate`（政策默认）
3. 如都未配置，加班工资 = 0（不自动使用旧版 ¥20）

### 5.2 计算
```
加班工资 = 加班小时数 * 加班小时单价
```
生成 salary_record_item:
- item_code: OVERTIME
- item_name: 加班工资
- quantity: 加班小时数
- unit_price: 小时单价
- amount: 计算结果
- source: ATTENDANCE / MANUAL_INPUT

### 5.3 财务调整
- 财务可以修改加班工资金额
- 必须填写原因（calculation_note）
- 修改记录到 operation_log

---

## 6. 提成设计（预留）

### 6.1 commission_type
- REVENUE_PCT: 按营收百分比
- PROFIT_PCT: 按利润百分比
- FIXED: 固定金额
- NONE: 无提成

### 6.2 数据来源
提成数据需要门店月度营收/利润数据（profit_entry 表），在后续版本接入。

---

## 7. 缺勤扣款设计

### 7.1 计算
```
日工资 = base_salary / 满勤天数
缺勤扣款 = 日工资 * 缺勤天数
```

### 7.2 满勤天数
默认: 当月自然日 - 4（4天为假设月休天数，后续从考勤系统获取）

---

## 8. 版本管理

- 每次工资生成时，记录使用的 salary_policy.id 和 version
- 修改政策时创建新版本（递增 version）
- 历史工资记录关联生成时的政策版本
- 不允许修改已用于计算的政策版本

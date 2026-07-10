# MySQL 迁移验证报告 — 工资模块

生成时间: 2026-07-10
验证环境: H2 (MySQL MODE) + JDK 21
真实 MySQL 环境: **BLOCKED — 本机未安装 MySQL**

---

## 1. 迁移链完整性

| 版本 | 文件名 | 工资相关变更 | H2 状态 |
|------|--------|-------------|---------|
| V1 | `V1__init_schema.sql` | 创建 `salary_record` 表（14 个工资项目列 + store_id/month/employee_name） | PASS |
| V3 | `V3__tenant_foundation.sql` | 添加 `salary_record.tenant_id` 列和索引 | PASS |
| V15 | `V15__employee_base_data.sql` | 创建 `employee` 表（工资生成的数据源） | PASS |
| V19 | `V19__salary_employee_identity_and_review.sql` | 添加 `employee_id`, `status`, `submitted_by`, `reviewed_by`, `reviewed_at` 列；创建 identity + review 索引 | PASS |
| V20 | `V20__exam_center_campaigns_and_assignments.sql` | 不涉及工资 | PASS |
| V21 | `V21__salary_status_workflow.sql` | 添加 `review_note`, `paid_at`, `version` 列；创建 `idx_salary_version` 索引 | PASS (H2) |
| V22 | `V22__salary_v21_mysql57_compat.sql` | MySQL 5.5/5.7 兼容层（新建） | PASS (H2) |
| V23 | `V23__salary_unique_employee_month.sql` | 添加 `uk_salary_tenant_employee_month` 唯一约束（新建） | PASS (H2) |

**版本号检查**: V1 到 V23 无重复，无跳跃。

---

## 2. V21 兼容风险评估

### 风险点
V21 使用了以下 MySQL 8.0.16+ 语法：
- `ADD COLUMN IF NOT EXISTS` — 在 MySQL 5.5/5.7 中报语法错误
- `CREATE INDEX IF NOT EXISTS` — 同上

### 解决方案
V22 使用 `information_schema` 条件判断 + 动态 SQL (`PREPARE/EXECUTE/DEALLOCATE`) 实现等价逻辑，兼容 MySQL 5.5+。

### MySQL 5.5 特殊注意事项
- `information_schema` probe 语法在 MySQL 5.5 中可用
- `PREPARE/EXECUTE/DEALLOCATE` 语法在 MySQL 5.5 中可用
- 未使用 `SIGNAL` 或 `GET DIAGNOSTICS`（MySQL 5.5 不支持）
- `timestamp null` 语法 → MySQL 5.5 仅允许一个 TIMESTAMP 列使用 CURRENT_TIMESTAMP，但 `paid_at` 无默认值，不受此限制

---

## 3. salary_record 列完整性

| 列名 | 类型 | 来源版本 | 说明 |
|------|------|---------|------|
| id | varchar(120) PK | V1 | 工资记录主键 |
| tenant_id | bigint | V3 | 租户隔离 |
| store_id | varchar(64) FK | V1 | 门店 |
| month | char(7) | V1 | 工资月份 (YYYY-MM) |
| employee_name | varchar(120) | V1 | 员工姓名（冗余，关联用 employee_id） |
| employee_id | varchar(120) | V19 | 员工档案外键（允许 NULL，兼容旧数据） |
| position | varchar(80) | V1 | 岗位 |
| attendance | varchar(80) | V1 | 出勤信息 |
| **14 个工资项目** | decimal(14,2) | V1 | base/social/post/meal/full_attendance/commission/overtime/seniority/late_night/subsidy/performance/deduct_uniform/return_uniform + gross |
| normal_hours/ot_hours/work_hours | decimal(10,2) | V1 | 工时字段 |
| vacation_left | decimal(10,2) | V1 | 假期余额 |
| vacation_note | varchar(255) | V1 | 假期备注 |
| status | varchar(40) | V19 | DRAFT/SUBMITTED/APPROVED/REJECTED/PAID/LOCKED |
| submitted_by | bigint | V19 | 提交人 |
| reviewed_by | bigint | V19 | 审核人 |
| reviewed_at | timestamp | V19 | 审核时间 |
| review_note | varchar(500) | V21 | 审核备注 |
| paid_at | timestamp | V21 | 发放时间 |
| version | int | V21 | 乐观锁版本号 |
| created_at/updated_at | timestamp | V1 | 创建/更新时间 |

---

## 4. 索引和约束

| 名称 | 类型 | 列 | 来源 |
|------|------|-----|------|
| idx_salary_store_month | INDEX | store_id, month | V1 |
| idx_salary_name | INDEX | employee_name | V1 |
| idx_salary_tenant_store_month | INDEX | tenant_id, store_id, month | V3 |
| idx_salary_employee_identity | INDEX | tenant_id, employee_id, month | V19 |
| idx_salary_review_status | INDEX | tenant_id, status, month | V19 |
| idx_salary_version | INDEX | tenant_id, employee_id, month, version | V21/V22 |
| **uk_salary_tenant_employee_month** | **UNIQUE** | tenant_id, employee_id, month | **V23** |
| fk_salary_store | FK | store_id → store_branch | V1 |
| fk_salary_tenant | FK | tenant_id → tenant | V3 |

---

## 5. H2 测试结果

### 全量测试
- 总测试数: 215
- 通过: 189
- 失败: 26（全部为预知的静态资源测试，与工资无关）
- 错误: 0

### 工资专项测试
- SalaryServiceTest: 5/5 通过
- SalaryControllerTest: 4/4 通过
- 覆盖: CRUD、角色隔离（店长/BOSS/财务）、状态流转、操作日志

---

## 6. 阻塞项

| 阻塞项 | 状态 | 说明 |
|--------|------|------|
| 真实 MySQL 迁移验证 | **BLOCKED** | 本机未安装 MySQL (`mysql: command not found`) |
| MySQL 5.5 兼容性验证 | **BLOCKED** | 需要 MySQL 5.5/5.7 实例验证 V22 |
| 唯一约束验证 | **BLOCKED** | 需要真实 MySQL 验证 `uk_salary_tenant_employee_month` 不会因重复数据失败 |
| 升级路径验证 | **BLOCKED** | 需要已有数据的 MySQL 实例验证 V1→V23 升级 |

---

## 7. 后续建议

1. 在有 MySQL 的环境中执行 `mysql -u root -p -e "SELECT VERSION()"` 确认版本
2. 空数据库从 V1 迁移到 V23
3. 有数据数据库从旧版本升级到 V23
4. 验证 `INSERT INTO salary_record ... ON DUPLICATE KEY` 被唯一约束阻止
5. 验证 V22 在 MySQL 5.7 中不报语法错误

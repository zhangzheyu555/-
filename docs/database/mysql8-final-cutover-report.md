# MySQL 8 唯一数据库最终收口报告

> 当前结论：**BLOCKED**  
> 更新时间：2026-07-12（Asia/Shanghai）  
> 说明：发布候选源码可复现，但仍有两个硬门禁：一是 Git 历史中的业务备份尚未净化；二是 V36→V37 尚未在 3307 业务库最终副本和最新 Jar 上完成升级、回归、回滚及切换验收。本报告不是切换 PASS 回执。

## 1. 目标状态

- 唯一运行数据库：MySQL 8.0.46，端口 3307，数据库 `store_profit_mysql8_final`。
- 最新后端先在 18082 并行验收，通过后切换 18081。
- 3306 的 MySQL 5.5 最终为 `Stopped + Disabled`；禁止运行时回退。
- 3309 只读恢复实例完成最终数据比对后停止。
- 原 MySQL 5.5 目录、原始 SQL、附件及校验清单保留到切换完成后至少 30 天。

## 2. 权威迁移源码

V1-V34 保持不变。V35-V37 已按归档、源成功历史和真实 MySQL 8 证据恢复到正式 MySQL 迁移目录，并进入最新候选 Jar。

| 版本 | 来源 | 文件 SHA-256 | Flyway checksum | 结果 |
|---|---|---|---:|---|
| V35 | Git index 权威 blob 与源成功历史 | `E957CC70D813BF5EDF3797A8E712B44ADEF35F2D0F9F8D55E545562F5FBFCCD3` | `-1710239295` | PASS |
| V36 | 源成功记录、权威 SHA 和旧 DDL 证据 | `F198EE24AC88BB8E8C6A1126C6C44F26297B227D6B7EF87559D247680C789A4E` | `-1238519929` | PASS |
| V37 | 归档任务 `019f4f46-31f1-7120-91f1-833dc74ed083` 的最终 MySQL 8 修正版 | `75712C479633E0180FB8178AC72AB32DB29B71CCB8CB94457B0B84820E25A06F` | `761638840` | PASS |

V37 恢复证据位于仓库外：

`C:\Users\34706\AppData\Local\AI-Profit-OS\v37-archive-recovery\20260712-archive-019f4f46\recovery-manifest.json`

manifest SHA-256：`12A296CD0001A035D32DF59FEC100ED3A3F79F729C4DF6854310233CCCC98568`。

失败 V37 checksum `1160061988` 只保留为事故证据，不作为最终可执行迁移。

## 3. 全新 MySQL 8 验证

在独立端口 3319、全新数据目录和全新数据库中，从 V1 迁移到 V37：

- MySQL：8.0.46；
- Flyway：37 条成功记录、失败记录 0；
- 业务基础表：73；
- `permission_catalog`：42 项；
- V37 权限表：3；
- `permission_version` 字段：2；
- `Flyway validate`：PASS；
- 第二次 `migrate`：no-op，历史 SHA 前后一致。

证据：

`C:\Users\34706\AppData\Local\AI-Profit-OS\v37-clean-mysql8\20260712-114614\evidence\v1-v37-clean-validation.json`

SHA-256：`048720A09E4DF5499253ECCACC99B83910AF7DA917B1E4A1C47804280AF0B5B0`。

## 4. 失败 V37 修复演练

使用原 MySQL 5.5 逻辑备份的固定兼容副本，在独立 3319 测试库重现了真实失败状态：

- 73 张基础表；
- Flyway 37 条记录，其中失败 V37 恰好 1 条；
- 失败 checksum 为 `1160061988`；
- 三张权限表均为 0 行；
- 两个 `permission_version` 字段均仅含默认值 1；
- 无视图、触发器、存储过程、函数或事件。

清理前逻辑备份：

`C:\Users\34706\AppData\Local\AI-Profit-OS\mysql8-final-cutover\20260712-115536\rehearsal-3319\pre-cleanup-full.sql`

SHA-256：`84C641D547F315DD6AFBD810475D61C70A7CDCFEE433D10C1C3CCABBFDB01AE1`。

演练顺序为：核验残留对象与行数 → 备份 → 只清理失败 V37 对象 → Flyway 11.7.2 `repair` → 核对 V1-V36 完全未变 → 执行最终 V37 → `validate` → 第二次 no-op `migrate`。

结果：

- V1-V36 规范化历史 SHA 前后均为 `6EC81AC454E24EB2496F8D11FC56816ACA0FC177EC3D8BE7CE3C84ED8853C196`；
- 最终 V37 checksum `761638840`、`success=1`；
- 权限 42 项、权限表 3 张、版本字段 2 个；
- 失败记录 0；
- 第二次迁移前后历史 SHA 均为 `0616B1747A973CE011592CE02002EF5270CF56089F6AB76A36C3ED171CAD1C99`。

演练证据：

`C:\Users\34706\AppData\Local\AI-Profit-OS\mysql8-final-cutover\20260712-115536\rehearsal-3319\v37-rehearsal-evidence.json`

SHA-256：`C83EF5CCEC4AEDDE133FEE02786E40EBCB647701385F7F34158246D2DCB9D7D7`。

正式 3307 只允许重复这套已验证流程。`repair` 只能移除失败记录，遗留对象必须先核验并清理，且必须与 `migrate` 使用同一迁移目录，符合 [Flyway repair 官方说明](https://documentation.red-gate.com/flyway/reference/commands/repair)。

## 5. 数据和附件保全

所有证据均在 Git 工作区外；原始 SQL 和原数据目录未修改。

| 证据 | 结果 |
|---|---|
| MySQL 5.5 原始逻辑备份 | SHA-256 `A3C791A54E232066B96C40703B60CB0A797F35829AB94A05915AA71614410077` |
| MySQL 8 固定兼容副本 | SHA-256 `E8180C1E74698191C2CCA3DEFE600C0093C323327B347C4CEFA0AC1CB6909E3F` |
| 3307 现有证据库 | `store_profit_mysql8`，保持不动 |
| 3307 离线快照清单 | SHA-256 `2737E6CA59EA93F44B14EE3DFF74A8BFEEB0E7532B8E8F7248987A3615FB4A23` |
| 3309 恢复源 | MySQL 5.5.62、loopback、`read_only=ON` |
| 培训图片 | 119 个 / 10,818,719 字节；manifest SHA-256 `93CB100D58B803F1AA451305FBF7A86719C2FB48D6CAEBF9F38209F82BCDF825` |
| 孤儿附件 | 3 个 / 281,199 字节；`ORPHAN_CONFLICT_NO_DATABASE_RECORD`，禁止自动关联 |

迁移继续采用逻辑导出/导入，不跨主版本复用数据目录，符合 [MySQL 官方升级路径](https://dev.mysql.com/doc/mysql-installation-excerpt/8.0/en/upgrade-paths.html)。

## 6. 候选构建和启动守卫

最新候选 Jar 在仓库外隔离副本构建，避免旧 18081 锁定工作区 Jar：

`C:\Users\34706\AppData\Local\AI-Profit-OS\mysql8-final-cutover\20260712-131352\build\backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar`

- Jar SHA-256：`6D596F05176E104B9D8DA536F68DAAC8EBC2E07A94FEE4190CC3F80BE565DC3B`；
- Jar 内 V35/V36/V37 SHA 与第 2 节完全一致；
- 后端完整 `mvn test`：301 项，失败 0、错误 0、跳过 0；
- 仓库外隔离 `mvn clean package`：PASS；
- Vue `vue-tsc -b` 与生产构建：PASS。

候选构建证据：

`C:\Users\34706\AppData\Local\AI-Profit-OS\mysql8-final-cutover\20260712-131352\candidate-build-evidence.json`

SHA-256：`A1EBAA0E10DA8F6EEAFAC682D8E8CC73772C9FCAC01D15E371AC3D839CAE9F31`。

新增启动守卫：

- STAGING/PRODUCTION 只允许 `127.0.0.1`、3307 和固定最终库名，不能用环境变量扩大批准范围；
- 连接后、Flyway 执行前再次核验 MySQL 版本、实际端口、当前数据库、非 root 本机账号和限库授权；
- 禁止独立 Flyway URL、账号或密码，Flyway 必须复用已经核验的应用 DataSource；
- 禁止数据库自动创建和 Flyway baseline；
- 启动脚本固定最终库、拒绝 root、删除 3306 与旧库默认回退。
- JDBC 与连接池使用短连接超时；健康接口只返回脱敏的数据库版本、端口、库名和 `LOCAL_SCOPED` 状态，不返回账号或凭据。

最终迁移与切换脚本已通过 Windows PowerShell 5.1 语法门禁：

| 脚本 | SHA-256 |
|---|---|
| `mysql8-logical-migration.ps1` | `B2EFA719D007C927455F6FA0D94F1E7A72825C0E82733A198276804F749B2CD7` |
| `mysql8-validate-migration.ps1` | `5CA914FF16494294DA1C55C05A49ECE7E7EBA95B100C79341FD0028C30FA3CD8` |
| `mysql8-repair-v37.ps1` | `B409A87E220BA1857C1D385A024C7584A44FDA3AF1104778BCA5B27B71FC744E` |
| `start-backend-windows.ps1` | `E269A5F00991AC3442CB91FD5091E591447BFD8EEE3688B6D1F945943DE6C7FC` |
| `mysql8-cutover.ps1` | `7374373AC23415E7CEA8F9647EA1D5D0399AA9D8C28B2C198E6C0EE7A66A51C7` |
| `mysql8-disable-mysql55.ps1` | `ECFCF9E72588AEB7ED4D00F419FDDE5F17D7C26D9015260EBD47954B12F429CF` |

## 7. 当前运行状态和阻塞项

| 项目 | 当前状态 |
|---|---|
| 3306 / MySQL 5.5 | Stopped、无监听；启动方式仍为 Auto，尚未 Disabled |
| 3307 / MySQL 8.0.46 | Running、仅 loopback；监听 PID 22544，服务 PID 28976；现有证据库未修改 |
| 3309 / MySQL 5.5.62 | Running、仅 loopback、只读；PID 31768 |
| 3319 演练实例 | 已正常停止；独立数据目录、日志和修复证据已保留 |
| 18081 | 旧进程 PID 32228 仍在，不能作为最终验收环境 |
| 18082 | 尚未启动 |
| 5173 | 现有前端 PID 28804 运行中，尚未切换到最终 18081 |

当前数据库直接执行阻塞：本机没有可用且已知的 3307 管理凭据；两次一次性本机 `init-file` 管理员确认均被取消。因此：

1. `store_profit_mysql8_final` 尚未从固定原始备份重建；
2. 3307 正式 V37 清理、repair 和最终迁移尚未执行；
3. 3309 对 3307 最终库的逐表、金额、附件校验尚未执行；
4. 18082、角色、浏览器和附件验收尚未执行；
5. 18081 未切换；3306 未禁用；3309 未停止。

没有 3307 管理授权前不得宣称完成，也不得禁用旧服务。

3307 配置管理员审计也未闭环：实际配置为 `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini`，SHA-256 `3858CDCC5F381A87A540149EFA662A1B9961797AE7B688589D4C7D1E74B6EC70`。现有配置已限定 3307 和 loopback，并使用独立绝对 datadir；但 `basedir`、`tmpdir` 未显式生效，`log-error` 为相对路径，MySQL80Test datadir、错误日志和临时目录 ACL 因非管理员权限尚未完成核验，配置父目录的继承写权限也需管理员复核并收紧。该项未通过前不得进入正式切换。

另有独立的发布门禁：业务备份虽然已从工作树和索引删除，但 Git HEAD/历史仍含旧 blob。按既有审批要求，本次任务不得改写历史或强推；历史净化必须另行审批、在镜像克隆演练并协调分支、PR、克隆和 Fork 后执行。因此即使数据库切换完成，仓库发布仍不能在该门禁关闭前宣称 PASS。

## 8. 后续强制顺序

1. 由 Windows 管理员批准一次性本机初始化，创建只绑定 `127.0.0.1`、仅授权最终库和演练库的随机迁移账号；初始化文件立即删除、配置逐字节恢复。
2. 重建 `store_profit_mysql8_final`；先执行 Pre-V37 全量数据一致性校验。
3. 在 3307 演练库重复第 4 节流程；PASS 后才对最终库执行。
4. 执行 Post-V37 固定差异校验并生成 `mysql8-validation.json`。
5. 使用本报告候选 Jar 在 18082 双启动，完成角色、越权、业务和附件验收，生成 `mysql8-release-evidence.json`。
6. 签名审批后切换 18081，证明实际只连接 3307。
7. 生成 Cutover PASS 回执后，将 MySQL 5.5 服务设为 Disabled，并停止 3309；不删除任何原始目录或备份。

## 9. 发布审批表

| 门禁 | 当前状态 |
|---|---|
| V35-V37 权威源码、SHA、checksum | PASS |
| 全新 MySQL 8 V1→V37 | PASS |
| 失败 V37 清理/repair 演练 | PASS（3319） |
| 73 表最终库重建 | **BLOCKED：3307 管理授权未完成** |
| 3309 与最终库完整性 | BLOCKED |
| 最新 Jar / 18082 双启动 | Jar 构建 PASS；18082 双启动 BLOCKED |
| 五个管理角色、学员、跨店 403 | BLOCKED |
| 119 图片及业务附件 | 数据已保全，浏览器验收 BLOCKED |
| 18081 只连接 3307 | BLOCKED |
| 3306 Stopped + Disabled | Stopped；Disabled BLOCKED |
| 3309 停止 | BLOCKED |
| 备份保留至少 30 天 | 已保全；截止日期待切换日确定 |
| MySQL80Test basedir/tmpdir/log/ACL 管理员审计 | BLOCKED |
| Git 历史业务备份净化 | **BLOCKED：需独立审批，本次禁止改写历史或推送** |

最终结论保持 **BLOCKED**。未提交、未推送、未改写 Git 历史。

# `store-data-backup.json` Git 历史净化方案

## 当前结论

- 工作区中的 `store-data-backup.json` 已删除，且根目录 `.gitignore` 已阻止同名文件再次加入。
- 该文件仍存在于历史提交中；同一 Git 对象还曾位于
  `backend/src/main/resources/static/门店数据备份.json`。普通删除提交不能移除历史对象。
- 历史改写会改变所有受影响提交的 SHA，并要求远端强制更新和所有协作者重新克隆，因此必须经过仓库负责人审批后单独执行。

## 执行前门禁

1. 冻结目标仓库的合并和推送，记录受影响的分支、标签与协作者。
2. 将远端镜像备份到仓库外受限目录，备份位置不得同步到公共云盘或 Git。
3. 按数据安全事件处理：确认备份中是否曾包含账号、Token、密钥或个人信息；存在泄露可能时先轮换相关凭据。
4. 由仓库负责人明确批准历史改写窗口和强制推送范围。

## 经审批后的建议命令

以下命令仅应在受限镜像副本中运行，本次整改不执行：

```powershell
git filter-repo --path store-data-backup.json --path "backend/src/main/resources/static/门店数据备份.json" --invert-paths --force
git log --all -- store-data-backup.json
git log --all -- "backend/src/main/resources/static/门店数据备份.json"
git fsck --full --no-reflogs --unreachable
```

验证两个 `git log` 均不再返回记录，并确认原敏感 blob 在全部 refs 中不可达后，按审批范围强制更新远端分支和标签。不要在开发工作区直接运行历史净化。

## 执行后动作

- 所有协作者删除旧克隆并重新克隆；禁止把旧分支合并回净化后的历史。
- 重新运行发布完整性、凭据扫描、后端测试、Flyway 和前端构建。
- 保留审批单、执行人、时间、验证哈希和凭据轮换记录，不保留原业务数据内容。

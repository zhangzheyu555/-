# 门店利润系统

这是一个纯前端静态部署包，直接打开 `index.html` 即可使用。

## 文件说明

- `index.html`: 系统入口页面
- `database.js`: 基础数据、账号和数据访问层
- `cloudbase.full.js`: 腾讯云 CloudBase SDK
- `store-data-backup.json`: 门店数据备份文件，可在系统内导入恢复

## 本地使用

双击 `index.html`，或使用任意静态服务器打开本目录。

默认登录密码：

- 管理员：`123`
- 老板：`boss888`
- 店长：使用对应门店的 `code` 或 `id`

## 部署

可以直接把本目录全部上传到 GitHub Pages、CloudBase 静态托管或任意静态网站服务。

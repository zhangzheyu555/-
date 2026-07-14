# AI Profit OS 生产域名 / HTTPS / WAF 部署方案

更新时间：2026-07-09

本文档用于 `store-profit-system-upload` 项目的生产部署准备。当前阶段只整理部署方案、Nginx 配置、systemd 服务和检查清单，不购买域名、不做实名认证、不提交 ICP 备案、不修改业务代码。

## 一、推荐上线顺序

建议按下面顺序推进：

1. 购买域名。
2. 完成域名实名认证。
3. 完成 ICP 备案或接入备案。
4. 先用 Nginx + HTTPS 直连源站上线。
5. 验证前端、后端 API、上传、登录、仓库业务稳定。
6. 再接入腾讯云 WAF。
7. WAF 稳定后收紧服务器防火墙和安全组。

不要一开始同时改 WAF、DNS、Nginx、后端、前端和数据库。生产排错应一次只变更一个层级。

## 二、目标架构

```text
用户浏览器
  -> 域名 HTTPS
  -> 腾讯云 Web 应用防火墙 WAF
  -> 云服务器 Nginx 80/443
  -> /      前端静态文件 /opt/store-profit/frontend/
  -> /api/  反向代理 Java 后端 127.0.0.1:8080
  -> MySQL  本机或内网访问
```

生产原则：

- Java 后端只监听 `127.0.0.1:8080`，不直接暴露公网。
- Nginx 是唯一公网 Web 入口。
- MySQL 只允许本机或内网访问，禁止公网 `3306`。
- 前端静态文件独立部署到 `/opt/store-profit/frontend/`。
- 修改前端只上传静态文件；修改 Java、SQL 迁移、Maven 依赖才重新打包并重启后端。

## 三、服务器目录规范

```text
/opt/store-profit/
├─ backend/
│  └─ store-profit-backend-0.1.0-SNAPSHOT.jar
├─ frontend/
│  ├─ index.html
│  ├─ database.js
│  └─ cloudbase.full.js
├─ env/
│  └─ store-profit.env
├─ logs/
└─ backup/
```

`/opt/store-profit/env/store-profit.env` 示例：

```bash
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3307
MYSQL_DATABASE=store_profit_mysql8_final
MYSQL_USERNAME=<3307最终库独立非root账号>
MYSQL_PASSWORD=请在服务器本机填写真实密码
APP_SEED_DEMO_ENABLED=false
APP_MIGRATION_AUTO_RUN=false
```

注意：

- 不要把真实密码提交到 Git。
- 生产环境不要开启演示数据自动写入。
- 生产环境迁移策略应先备份数据库，再重启后端执行 Flyway。

## 四、域名购买和实名认证

建议在腾讯云购买域名，原因是后续 ICP 备案、SSL 证书、WAF、DNS 都在同一控制台里管理，减少跨平台排错成本。

可选域名方向：

```text
ai-profit-os.com
aiprofitos.com
storeprofit.cn
你的品牌名.com
```

用户必须自己完成：

- 域名购买。
- 域名实名认证。
- 域名持有人信息确认。
- 营业执照或个人实名资料提交。

Codex 不处理实名认证、人脸核验、营业执照、短信验证等人工步骤。

## 五、ICP 备案说明

如果服务器在中国大陆地域，例如腾讯云轻量云上海、广州、北京等，正式用域名访问网站通常需要 ICP 备案或接入备案。

腾讯云 WAF FAQ 也说明，中国大陆地区接入的域名必须按工信部要求进行 ICP 备案。

备案通常包含：

1. 备案主体信息。
2. 网站负责人信息。
3. 域名信息。
4. 服务器或云资源校验。
5. 真实性核验。
6. 腾讯云初审。
7. 管局审核。

备案未完成前：

- 不建议把大陆服务器正式绑定生产域名。
- 可继续用公网 IP 或临时测试域名做内部验证。
- 不要对外宣传正式访问地址。

## 六、DNS 解析方案

### 阶段 1：不接 WAF，先稳定上线

DNS 记录：

```text
类型：A
主机记录：@
记录值：服务器公网 IP
```

如果使用 `www`：

```text
类型：CNAME 或 A
主机记录：www
记录值：@ 或服务器公网 IP
```

阶段 1 目标：

- 验证 `https://你的域名` 能打开前端。
- 验证 `https://你的域名/api/health` 正常。
- 验证登录、上传、仓库业务、打印单下载。

### 阶段 2：接入腾讯云 WAF

WAF 添加域名后会给出 CNAME。DNS 改为：

```text
类型：CNAME
主机记录：@
记录值：WAF 提供的 CNAME
```

腾讯云 WAF 文档说明，SaaS 型 WAF 推荐通过 CNAME 接入，避免直接解析到 WAF VIP 或错误 IP。

## 七、HTTPS 证书方案

### 不接 WAF 时

证书安装在 Nginx：

```text
/etc/nginx/ssl/example.com_bundle.crt
/etc/nginx/ssl/example.com.key
```

对应模板见：

- `docs/nginx-production-ai-profit-os.conf`

### 接入 WAF 后

推荐把证书托管到 WAF，由 WAF 处理浏览器到 WAF 的 HTTPS。

源站回源可以选择：

- WAF -> 源站 Nginx 443：更安全，源站也保留 HTTPS。
- WAF -> 源站 Nginx 80：配置简单，但回源链路不加密。

生产建议优先使用 HTTPS 回源。

## 八、Nginx 前后端分离配置

生产 Nginx 模板见：

- `docs/nginx-production-ai-profit-os.conf`

关键规则：

- `80` 自动跳转 `443`。
- `443` 开启 SSL。
- `root /opt/store-profit/frontend`。
- `/api/` 代理到 `http://127.0.0.1:8080/api/`。
- `index.html` 禁用强缓存。
- `client_max_body_size 20m`，满足附件、图片上传。
- 添加基础安全响应头。

启用配置示例：

```bash
sudo cp docs/nginx-production-ai-profit-os.conf /etc/nginx/conf.d/ai-profit-os.conf
sudo nginx -t
sudo systemctl reload nginx
```

## 九、systemd 后端服务

systemd 模板见：

- `docs/store-profit-systemd.service`

安装示例：

```bash
sudo cp docs/store-profit-systemd.service /etc/systemd/system/store-profit.service
sudo systemctl daemon-reload
sudo systemctl enable store-profit
sudo systemctl restart store-profit
sudo systemctl status store-profit
```

日志查看：

```bash
journalctl -u store-profit -n 100 --no-pager
journalctl -u store-profit -f
```

## 十、服务器防火墙和安全组

腾讯云轻量服务器防火墙建议：

开放：

```text
TCP 80    0.0.0.0/0
TCP 443   0.0.0.0/0
TCP 22    仅管理员固定 IP
```

关闭公网访问：

```text
TCP 8080   Java 后端
TCP 18080  临时测试端口
TCP 3306   MySQL
```

腾讯云轻量应用服务器防火墙文档提示，添加规则时应按需配置端口和允许访问 IP，遵循最小授权原则。

接入 WAF 后：

- 如果能确认 WAF 回源 IP 段，可进一步限制 `80/443` 只允许 WAF 回源 IP。
- 如果暂时不能确认 WAF 回源 IP，先保持 `80/443` 开放，但必须启用 WAF 防护和 HTTPS。

## 十一、MySQL 安全要求

生产 MySQL 原则：

- 不开放公网 `3306`。
- 后端通过 `127.0.0.1` 或内网地址访问。
- 使用独立数据库用户，不建议生产直接用 `root`。
- 数据库密码放在 `/opt/store-profit/env/store-profit.env`，不要写入 Git。
- 上线前备份，升级前备份。

建议创建独立用户：

```sql
create user 'store_profit'@'127.0.0.1' identified by '强密码';
grant all privileges on store_profit.* to 'store_profit'@'127.0.0.1';
flush privileges;
```

## 十二、腾讯云 WAF 接入流程

建议在网站用 HTTPS 源站直连稳定后，再接入 WAF。

步骤：

1. 购买腾讯云 WAF 实例。
2. 进入 Web 应用防火墙控制台。
3. 选择接入管理 / 域名接入。
4. 添加防护域名。
5. 填写源站 IP：服务器公网 IP。
6. 配置源站端口：`80` 或 `443`。
7. 配置 HTTPS 证书。
8. 开启基础防护策略：
   - SQL 注入
   - XSS
   - 文件上传检测
   - 恶意扫描
   - 高频访问限制
9. 获取 WAF CNAME。
10. 到 DNS 控制台把域名解析改为 CNAME 到 WAF。
11. 测试访问。
12. 查看 WAF 攻击日志和访问日志。

腾讯云 WAF 域名管理文档说明，可以在接入管理的域名接入页面添加域名、开启防护开关，并查看接入状态。

## 十三、上线验证清单

### 服务器本机

```bash
curl http://127.0.0.1:8080/api/health
ss -lntp
systemctl status store-profit
journalctl -u store-profit -n 100 --no-pager
```

期望：

- `127.0.0.1:8080` 正常。
- Java 不监听 `0.0.0.0:8080`。
- MySQL 不开放公网。

### Nginx

```bash
nginx -t
systemctl reload nginx
curl -I https://你的域名
curl https://你的域名/api/health
```

期望：

- HTTP 自动跳转 HTTPS。
- 首页返回 `200`。
- `/api/health` 返回正常。
- `/api/**` 没有被前端路由拦截。

### 浏览器

验证：

- `https://你的域名/index.html` 能打开。
- 登录正常。
- 今日待办加载正常。
- 仓库中心加载正常。
- 附件上传正常。
- PDF 打印单下载正常。

### WAF 后

验证：

- DNS 已解析到 WAF CNAME。
- WAF 接入状态正常。
- WAF 访问日志有请求。
- 常规访问没有被误拦截。
- 上传和 PDF 下载没有被误拦截。

## 十四、回滚方案

### 前端回滚

```bash
ls -lt /opt/store-profit/backup/frontend/
sudo cp /opt/store-profit/backup/frontend/某次备份/index.html /opt/store-profit/frontend/index.html
sudo nginx -t
sudo systemctl reload nginx
```

### 后端回滚

```bash
sudo systemctl stop store-profit
sudo cp /opt/store-profit/backup/backend/store-profit-backend-上一个版本.jar /opt/store-profit/backend/store-profit-backend-0.1.0-SNAPSHOT.jar
sudo systemctl start store-profit
journalctl -u store-profit -n 100 --no-pager
```

### WAF 回滚

如果 WAF 接入后异常：

1. DNS 从 WAF CNAME 改回源站 A 记录。
2. 临时关闭 WAF 防护或删除接入域名。
3. 确认源站 Nginx HTTPS 仍可直连。

注意：DNS 回滚会受 TTL 影响，不一定立即对所有用户生效。

## 十五、不要做的事

不要：

- 删除旧前端。
- 修改业务代码来适配 WAF。
- 把 MySQL 暴露公网。
- 让 Java `8080` 直接公网访问。
- 把数据库密码写进 Git。
- 在文档里写真实密钥。
- 未备案前强行用中国大陆服务器正式绑定域名上线。
- 接 WAF 后马上关闭所有源站访问，先观察日志再收紧。

## 十六、人工步骤和 Codex 可执行步骤

用户必须自己完成：

```text
1. 买域名
2. 域名实名认证
3. ICP 备案
4. WAF 购买确认
5. SSL 证书申请或授权
```

Codex 后续可以继续执行：

```text
1. 配服务器目录
2. 上传前端静态文件
3. 上传后端 jar
4. 配 systemd 服务
5. 配 Nginx
6. 配 HTTPS
7. 调整服务器防火墙端口
8. 验证接口和页面
9. 部署后排错
```

## 十七、官方参考

- 腾讯云 WAF 域名管理：https://cloud.tencent.com/document/product/627/64316
- 腾讯云 WAF 新手常见问题：https://cloud.tencent.com/document/product/627/49179
- 腾讯云 ICP 首次备案：https://cloud.tencent.com/document/product/243/97668
- 腾讯云 Nginx SSL 证书安装：https://cloud.tencent.com/document/product/400/35244
- 腾讯云轻量应用服务器防火墙管理：https://cloud.tencent.com/document/product/1207/44577

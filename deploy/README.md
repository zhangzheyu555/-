# 部署总门 · 使用说明

这套配置在整个网站前面加**一道登录门**（nginx 反向代理 + HTTP Basic Auth）。
外网陌生人要先过这道门才能碰到系统，从而堵住 `/api/storage` 无鉴权、
账号密码可被直接读走等一系列洞——**不改任何业务代码**。

> 它是"总门"：所有店长共用这一道门的账号密码。进了门之后，各自再用系统里的
> 门店账号登录。它挡的是"互联网上任何人"，不做按人区分（按人区分是另一个更大的改造，
> 见项目问题清单里的"把登录搬到后端"）。

---

## 前提

- 一台 Linux 服务器（有公网 IP 或域名）。
- 后端 Spring Boot 已在服务器上跑起来，监听 `127.0.0.1:8080`
  （启动方式和本机一样：`mvn spring-boot:run`，或打成 jar 用 `java -jar`）。
- 识别服务在 `127.0.0.1:8000`（如果这台服务器也要跑拍照识别）。

---

## 三步装门

### 1. 装 nginx，放配置

```bash
sudo apt update && sudo apt install -y nginx apache2-utils
# 把本目录的 nginx-gateway.conf 复制过去
sudo cp nginx-gateway.conf /etc/nginx/conf.d/storeprofit.conf
# 打开改一行：把 server_name 的 _ 换成你的域名（没有域名可先留 _ 用 IP 访问）
sudo nano /etc/nginx/conf.d/storeprofit.conf
```

### 2. 创建门的账号密码

```bash
# 第一次：创建密码文件并加一个账号（比如 store）。回车后输两遍密码。
sudo htpasswd -c /etc/nginx/storeprofit.htpasswd store
# 以后再加人（不要再带 -c，否则会覆盖）：
sudo htpasswd /etc/nginx/storeprofit.htpasswd manager2
```

把这个"门账号 + 密码"发给店长们，让他们打开网页时先用它进门。

### 3. 生效

```bash
sudo nginx -t          # 检查配置语法，必须 OK
sudo systemctl reload nginx
```

现在访问 `http://你的域名或IP/`，会先弹出一个浏览器登录框——这就是总门。
输对了才进得去系统的登录页。

---

## ⚠️ 一定要配 HTTPS

Basic Auth 的密码在 **HTTP 明文下等于没锁**（能被网络中间人截获）。
上了公网**务必**配 HTTPS：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d 你的域名        # 自动签证书并改 nginx
```

certbot 会自动帮你把配置改成 HTTPS。或者手动参照 `nginx-gateway.conf`
文件底部注释里的 HTTPS 版本。

---

## 验证门有没有装好

装好后，在**你自己电脑**上（不是服务器上）测：

```bash
# 不带门密码 → 应该返回 401，说明陌生人被挡住了
curl -i http://你的域名/api/storage?key=accounts
# 期望看到：HTTP/1.1 401 Unauthorized

# 带门密码 → 正常
curl -i -u store:你设的密码 http://你的域名/api/storage?key=accounts
```

如果第一条返回的是数据而不是 401，说明门没生效，检查 nginx 配置有没有 reload。

---

## 注意事项

- **后端别再对公网直接开 8080。** 装了门之后，要用防火墙把 8080 只留给本机：
  ```bash
  sudo ufw allow 80,443/tcp
  sudo ufw deny 8080/tcp
  sudo ufw enable
  ```
  否则别人绕过 nginx 直接敲 `http://你的IP:8080/api/storage` 照样能进——门就白装了。
- 识别服务的 8000 端口同理，只留本机，别对公网开。
- 上传大图：配置里已把上限放到 30MB（后端是 25MB），够手机原图用。

# MapAdKiller 服务器公告

给你（作者）一个**能实时推送给所有用户**的公告通道：版本更新、事故通知、紧急提醒。
三端内置（高德 / 百度 / 腾讯）+ LSPosed 模块版都带这个功能。

---

## 为什么这么设计（你的服务器只有 2GB 内存）

| 方案 | 常驻内存 | 说明 |
|---|---|---|
| **静态 JSON + 现有 nginx**（本方案） | **+0 MB** | 公告就是一个文件，nginx 本来就在跑 |
| 自建 Node/Python 服务 | +80~150 MB | 为了一个公告不值得 |
| 数据库 + 后台管理面板 | +300 MB 起 | 2GB 机器上会和 nginx/其它服务抢内存 |

推送时只在服务器上临时起一个 `bash`（几 MB RSS，毫秒级退出），**不常驻任何进程**。

---

## 一、部署（一次性，3 分钟）

把 `tools/announce/` 整个目录传到服务器，然后在服务器上：

```bash
cd /path/to/announce
chmod +x deploy.sh push.sh status.sh
sudo ./deploy.sh
```

它会做四件事：
1. 建 `/var/www/html/mc/`，放一个空公告（避免客户端 404）
2. 设权限（nginx 可读文件、目录 755）
3. 把推送脚本和签名密钥装到 **`/opt/mc-announce/`**（`700`，webroot 之外，公网下载不到）
4. 自检 `http://127.0.0.1/mc/announce.json` 返回 200

> **不需要改 nginx 配置、不需要 reload。** 你的站根目录就是 `/var/www/html`，
> 文件放进去直接就能通过 `http://47.109.49.93/mc/announce.json` 访问。

验证（在你自己电脑上）：
```
curl http://47.109.49.93/mc/announce.json
```

---

## 二、推送一条公告

### 方式 A：在服务器上
```bash
sudo /opt/mc-announce/push.sh \
  --title "2.3.1 已发布" \
  --body  "修复了高德开屏页被拦截的问题，建议更新。" \
  --level info \
  --url   "http://47.109.49.93/mc/2.3.1.apk"
```

### 方式 B：从你自己的 Windows 电脑（不用登录服务器）
在 PowerShell 里（会提示输密码）：
```powershell
Get-Content tools\announce\push.sh -Raw | ssh root@47.109.49.93 "cat > /tmp/push.sh && sudo bash /tmp/push.sh --title '2.3.1 已发布' --body '修复了高德开屏页被拦截的问题'"
```
更省事的做法：把 `push.sh` 一次性 scp 上去，之后每次只跑 ssh 那一半。

### 参数

| 参数 | 说明 |
|---|---|
| `--title` | 标题（必填） |
| `--body` | 正文（必填） |
| `--level` | `info`（普通）/ `warn`（警告色）/ `force`（不可取消，用户必须点「知道了」） |
| `--url` | 「查看详情」跳转地址，可空 |
| `--min` / `--max` | 只对 `versionCode` 在某区间的客户端展示（老版本用户收不到新版本公告时用） |
| `--expire` | unix 秒，过期自动不展示 |
| `--clear` | 推一条空公告 = 全网撤回 |

### 撤回 / 清空
```bash
sudo /opt/mc-announce/push.sh --clear
```

### 看当前线上公告
```bash
sudo /opt/mc-announce/status.sh
```

---

## 三、客户端怎么拿到

- **只在启动后 3 秒**和**打开面板时**检查一次，**最长 6 小时才联网一次**
- 带 `If-None-Match`，服务端返回 `304` 就结束，不解析不写盘
- connect/read 超时各 3 秒，失败静默，**不重试轰炸**
- 同一个 `id` 只提醒一次；`announce_on` 关掉就完全不联网
- 面板「其他」页有两个入口：`接收服务器公告`（开关）、`最新公告`（重看）
- 日志：`event=announce_fetch|announce_show|announce_skip`

---

## 四、安全（重要，逐条看）

1. **客户端只认 `http://47.109.49.93/` 前缀**，URL 被改成别的域名会直接丢弃。
2. **每条公告带 HMAC-SHA256 签名**（`sig` 字段，密钥 `secret.txt`）。
   因为是 HTTP 明文，中间人可以篡改内容 —— 签名能拦住伪造公告（比如塞钓鱼链接）。
   ⚠ 但密钥是编译进 APK 的，**只能防中间人，防不住逆向**。够用，别当强安全。
3. **绝对不要把 SSH 密码 / root 凭据写进 APK**。客户端只知道一个公网 URL。
4. 之后有条件建议上 HTTPS（Let's Encrypt 免费，nginx 加个 `ssl_certificate` 即可，
   内存开销 <10 MB），那时签名仍建议保留 —— 它防的是「服务器被换文件」这种情况。
5. `secret.txt` 会进 git。它本来就在 APK 里，不算泄露；但要换密钥时，
   **必须同时改 APK 和服务端**，否则老客户端会丢弃所有新公告（`event=announce_badsig`）。

---

## 五、排错

| 现象 | 检查 |
|---|---|
| 客户端收不到 | `curl http://47.109.49.93/mc/announce.json` 通不通；`id` 有没有比上次大 |
| 收到但被丢弃 | 日志里搜 `announce_badsig` → 服务端和 APK 的密钥不一致 |
| 一直不弹 | 客户端 `announce_on` 是否关着；6 小时节流；`expireAt` 是否已过；`min/maxVersionCode` 不匹配 |
| 推了没生效 | nginx 缓存？`add_header Cache-Control` 已有 ETag 语义，正常不会；看 `status.sh` 确认文件真的变了 |
| 磁盘 | `history.log` 只留最近 200 条，不会涨 |

---

## 六、JSON 契约（客户端按这个解析）

```json
{
  "id": 1767225600,
  "title": "2.3.1 已发布",
  "body": "修复了高德开屏页被拦截的问题，建议更新。",
  "level": "info",
  "url": "http://47.109.49.93/mc/2.3.1.apk",
  "minVersionCode": 0,
  "maxVersionCode": 0,
  "expireAt": 0,
  "sig": "9f2c...（HMAC-SHA256 of  id|title|body ）"
}
```

---

## 七、域名没备案，怎么用 `ldxm666.icu`

**结论先说**：未备案域名在国内的 80 端口经常被拦，443 一般能通。所以客户端设计成
**双通道自动降级** —— 先试域名，失败就用 IP，用户无感。

```
首选: https://ldxm666.icu/mc/announce.json
兜底: http://47.109.49.93/mc/announce.json
```

### 路线 A：Cloudflare 代理 + Flexible SSL（推荐，源站零改动）

这是未备案域名最省事的用法，**服务器上一张证书都不用装**。

1. 把 `ldxm666.icu` 的 NS 指到 Cloudflare（免费套餐够用）
2. DNS 里加一条 `A` 记录：`@ → 47.109.49.93`，**开橙色云（Proxied）**
3. SSL/TLS 模式选 **Flexible**（客户端 → Cloudflare 走 HTTPS，Cloudflare → 你的服务器走 HTTP 80）
4. 完事。`https://ldxm666.icu/mc/announce.json` 就能访问

为什么这条路对你最合适：
- 源站不用装证书、不用开 443、nginx 一行不用改
- 你的真实 IP 被 Cloudflare 挡住，少挨爆破
- 内存开销 **0**（Cloudflare 是外部服务，不占你 2GB）

⚠ 代价：国内访问 Cloudflare 边缘节点可能慢一点（首字节几百毫秒）。公告是 6 小时拉一次的小文件，**完全可接受**。

### 路线 B：Let's Encrypt 证书 + 让 nginx 直接开 443

```bash
EMAIL=you@example.com DOMAIN=ldxm666.icu ./tls-setup.sh
```

- 80 端口能通 ⇒ 走 HTTP-01，自动签好
- 80 被拦（未备案常见）⇒ 脚本会提示改走 **DNS-01**（需要 DNS 服务商的 API Token，**不需要任何入站端口**）
- 拿到证书后，把 `nginx-mc.conf` 里 ② 那段 `server{}` 取消注释，`server_name` 改成 `ldxm666.icu`，
  然后 `nginx -t && systemctl reload nginx`

内存：certbot 只在申请时跑一次，**不常驻**；nginx 多一个 443 server 块，会话缓存 1MB。

### 路线 C：只用 IP（最保守）

不改任何东西，客户端兜底地址就是它。缺点是 `http://` 明文、IP 写死在包里（换机要发新版）。

### 为什么客户端要保留 IP 兜底

未备案域名有被**阻断/DNS 污染**的现实风险 —— 一旦发生，只认域名的客户端会永久收不到公告，
而你**恰恰是在需要发事故通知的时候**才发现它失效。IP 兜底保证最坏情况下通道还在。

代价是 IP 写死在 APK 里（换服务器要发版）。这是有意的取舍：**通道可用性 > 可迁移性**。

### 顺带一句实话

未备案域名在境内对外提供服务，本身是个合规灰区。我按你的要求把技术方案做通，
但把风险说清楚：**域名随时可能被拦，这就是为什么客户端必须留 IP 兜底**。
另外域名如果哪天被墙，`announce_fallback` 这条日志的频率会明显上升 —— 那就是信号。

#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 给 ldxm666.icu 签一张免费证书（Let's Encrypt）。
#
#   前提：80 端口能被公网访问到（未备案域名在国内有时会被拦）。
#   如果 80 被拦，用 DNS-01 验证（见文件末尾的 CF 方案），完全不需要入站端口。
#
#   内存开销：certbot 只在申请时跑一次（几十 MB，几秒），
#   之后只有 nginx 用它，**不常驻**。2GB 机器完全没压力。
# ─────────────────────────────────────────────────────────────
set -euo pipefail

DOMAIN="${DOMAIN:-ldxm666.icu}"
EMAIL="${EMAIL:-}"          # ← 填一个邮箱，证书到期会提醒

if [ -z "$EMAIL" ]; then
  echo "请先设置邮箱: EMAIL=you@example.com ./tls-setup.sh"
  exit 2
fi

echo "==> 安装 certbot（Ubuntu/Debian）"
if ! command -v certbot >/dev/null 2>&1; then
  apt-get update -qq
  apt-get install -y -qq certbot python3-certbot-nginx
fi

echo "==> 先确认 80 端口对这个域名可达"
# 这一步很关键：未备案域名经常在这里就失败，那就说明必须走 DNS-01 或 Cloudflare
if curl -s -m 8 -o /dev/null -w "%{http_code}" "http://${DOMAIN}/mc/announce.json" | grep -qE '^(200|404)$'; then
  echo "    80 OK，走 HTTP-01 验证"
  certbot certonly --nginx -d "$DOMAIN" --non-interactive --agree-tos -m "$EMAIL" || {
    echo "!! nginx 插件失败，改用 webroot"; 
    certbot certonly --webroot -w /var/www/html -d "$DOMAIN" --non-interactive --agree-tos -m "$EMAIL";
  }
else
  echo "!! 80 端口不可达（未备案域名常见）。"
  echo "   改用 DNS-01：需要 DNS 服务商的 API Token。"
  echo "   如果你把 DNS 托管在 Cloudflare，装插件后这样签："
  echo "     apt-get install -y python3-certbot-dns-cloudflare"
  echo "     echo 'dns_cloudflare_api_token = <你的Token>' > /root/.secrets/cf.ini"
  echo "     chmod 600 /root/.secrets/cf.ini"
  echo "     certbot certonly --dns-cloudflare --dns-cloudflare-credentials /root/.secrets/cf.ini \\"
  echo "             -d '$DOMAIN' -d '*.$DOMAIN' --non-interactive --agree-tos -m '$EMAIL'"
  echo
  echo "   ★ 但更省事的做法见 README 第七节：Cloudflare 代理 + Flexible SSL，"
  echo "     **源站一张证书都不用装**。"
  exit 1
fi

echo
echo "==> 证书位置"
ls -l "/etc/letsencrypt/live/${DOMAIN}/" || true
echo
echo "==> 自动续期（certbot 自带 timer，确认一下）"
systemctl list-timers 2>/dev/null | grep -i certbot || true
echo
echo "下一步：把 nginx-mc.conf 里 ② 那段 server{} 取消注释，把 server_name 改成 $DOMAIN，然后 nginx -t && systemctl reload nginx"

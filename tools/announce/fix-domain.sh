#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 打通「未备案域名」访问：在高端口上再开一个 server，
# 绕开阿里云对 80/443 按 Host 头的 ICP 拦截。
#
#   前提：deploy.sh 已经跑过（/var/www/html/mc/announce.json 存在）
#   用法：sudo ./fix-domain.sh
#   验证：curl http://ldxm666.icu:28443/mc/announce.json
# ─────────────────────────────────────────────────────────────
set -euo pipefail

DOMAIN="${DOMAIN:-ldxm666.icu}"
PORT="${PORT:-21119}"
CONF="/etc/nginx/conf.d/mc-domain.conf"

if [ ! -f /var/www/html/mc/announce.json ]; then
  echo "!! 先跑 deploy.sh（/var/www/html/mc/announce.json 不存在）" >&2
  exit 2
fi

echo "==> 写入 $CONF"
sed -e "s/listen 21119;/listen ${PORT};/" \
    -e "s/server_name ldxm666.icu;/server_name ${DOMAIN};/" \
    "$(dirname "$0")/nginx-domain.conf" > "$CONF"
chmod 644 "$CONF"

echo "==> 检查配置"
nginx -t

echo "==> reload"
systemctl reload nginx

echo
echo "==> 本机自检（这个一定通，因为不带公网 Host）:"
curl -s -o /dev/null -w "  http://127.0.0.1:${PORT}/mc/announce.json -> %{http_code}\n" \
     "http://127.0.0.1:${PORT}/mc/announce.json" || true

echo
echo "==> 带域名自检（关键）:"
curl -s -o /dev/null -w "  http://${DOMAIN}:${PORT}/mc/announce.json -> %{http_code}\n" \
     -H "Host: ${DOMAIN}" "http://127.0.0.1:${PORT}/mc/announce.json" || true

echo
echo "完成。从公网验证："
echo "  curl http://${DOMAIN}:${PORT}/mc/announce.json"
echo
echo "注意：如果运营商/防火墙不放行 ${PORT}，需要在这台 ECS 的【安全组】里放行 TCP ${PORT}。"
echo "      阿里云默认安全组只开 22/80/443 —— 这一步经常被漏掉。"
echo "      另: 21115~21119 是 RustDesk 默认端口，若已跑 RustDesk 会占用，先 ss -lntp 确认。"

#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# MapAdKiller 公告服务 —— 部署脚本（在服务器上以 root 执行一次）
#
#   为什么这么部署：你只有 2GB 内存。
#   所以**不加任何常驻进程** —— 复用已经在跑的 nginx，公告就是一个静态 JSON 文件。
#   额外内存占用 = 0。推送时临时跑一个 bash（几 MB，毫秒级就退出）。
# ─────────────────────────────────────────────────────────────
set -euo pipefail

WEBROOT="${WEBROOT:-/var/www/html}"
DIR="$WEBROOT/mc"

echo "==> 目标目录: $DIR"
mkdir -p "$DIR"

# 1) 首次部署时放一个空公告，避免客户端拿到 404
if [ ! -f "$DIR/announce.json" ]; then
  cp "$(dirname "$0")/announce.json" "$DIR/announce.json"
  echo "==> 已放初始 announce.json"
else
  echo "==> announce.json 已存在，保留"
fi
if [ ! -f "$DIR/version.json" ]; then
  cp "$(dirname "$0")/version.json" "$DIR/version.json"
  echo "==> 已放初始 version.json"
else
  echo "==> version.json 已存在，保留"
fi

# 2) 权限：nginx 需要可读
chmod 755 "$DIR"
chmod 644 "$DIR/announce.json"
chmod 644 "$DIR/version.json"

# 3) 推送脚本和密钥，放在 webroot 之外（不能被公网下载到！）
mkdir -p /opt/mc-announce
cp "$(dirname "$0")/push.sh" /opt/mc-announce/push.sh
cp "$(dirname "$0")/push-version.sh" /opt/mc-announce/push-version.sh
cp "$(dirname "$0")/secret.txt" /opt/mc-announce/secret.txt
chmod 700 /opt/mc-announce
chmod 700 /opt/mc-announce/push.sh
chmod 700 /opt/mc-announce/push-version.sh
chmod 600 /opt/mc-announce/secret.txt
chown -R root:root /opt/mc-announce
echo "==> 推送脚本已装到 /opt/mc-announce/（仅 root 可执行）"

# 4) 自检：nginx 配置里有没有把 /mc 挡掉（一般不会有）
if command -v nginx >/dev/null 2>&1; then
  echo "==> nginx 版本: $(nginx -v 2>&1)"
fi

echo
echo "==> 验证（在本机）:"
curl -s -o /dev/null -w "  http://127.0.0.1/mc/announce.json -> %{http_code}\n" http://127.0.0.1/mc/announce.json || true
echo
echo "部署完成。推送公告:"
echo "  sudo /opt/mc-announce/push.sh --title '2.3.1 已发布' --body '修复了高德开屏页被拦截的问题'"

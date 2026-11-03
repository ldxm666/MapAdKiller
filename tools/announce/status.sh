#!/usr/bin/env bash
# 看一眼当前线上公告是什么（在服务器上跑）
set -euo pipefail
WEBROOT="${WEBROOT:-/var/www/html}"
echo "--- 文件 ---"
ls -l "$WEBROOT/mc/announce.json"
echo "--- 内容 ---"
cat "$WEBROOT/mc/announce.json"
echo "--- 最近 10 次推送 ---"
tail -n 10 /opt/mc-announce/history.log 2>/dev/null || echo "(无历史)"
